package com.xiaozhi.ai.tts.providers;

import com.xiaozhi.utils.DateUtils;
import com.xiaozhi.ai.tts.TtsService;
import com.xiaozhi.ai.tts.XiaozhiTtsOptions;
import com.xiaozhi.ai.utils.HttpUtil;
import com.xiaozhi.common.model.bo.ConfigBO;
import com.xiaozhi.utils.JsonUtil;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * GPT-SoVITS 本地 TTS 服务（自建音色，跨语言零样本克隆）。
 *
 * <p>对接本机跑的 GPT-SoVITS api_v2（{@code POST /tts}），把文本合成成 wav。
 * 音频格式转换、Opus 编码、60ms 调度等全部由既有链路负责，本类只做"文本 → 音频文件"。
 *
 * <p><b>音色来源</b>：参考音频（{@code ref_audio_path}）+ 其语言（{@code prompt_lang}）。
 * 参考音频是英文、合成中文时即"跨语言克隆"——音色由音频决定，与语言无关。
 *
 * <p><b>配置来源</b>：{@code apiUrl} 取自 {@code sys_config}（库里的配置行），
 * 其余参数走环境变量（读不到用默认值），符合本项目"配置走环境变量"的约定：
 * <ul>
 *   <li>{@code GPTSOVITS_REF_AUDIO}   参考音频路径（默认 {@value #DEFAULT_REF_AUDIO}）</li>
 *   <li>{@code GPTSOVITS_PROMPT_LANG} 参考音频语言，默认 {@code en}</li>
 *   <li>{@code GPTSOVITS_TEXT_LANG}   合成语言，默认 {@code zh}</li>
 *   <li>{@code GPTSOVITS_SAMPLE_STEPS} 采样步数，默认 32</li>
 *   <li>{@code GPTSOVITS_POST_FILTER}  ffmpeg 后处理滤镜（留空=关闭后处理）</li>
 *   <li>{@code GPTSOVITS_FFMPEG}      ffmpeg 可执行文件，默认 {@code ffmpeg}（走 PATH）</li>
 *   <li>{@code GPTSOVITS_WARMUP}      是否启动时预热 GPU，默认 {@code true}</li>
 * </ul>
 *
 * <p><b>为什么需要后处理</b>：零样本克隆会把参考音频的底噪"复制"到输出里，表现为电流声/嘶声。
 * 实测 {@code afftdn + highpass + loudnorm} 能显著压掉，代价约 183ms（6 秒音频）。
 * ffmpeg 不可用时自动跳过（记警告，仍返回原始 wav），不影响主链路。
 */
@Slf4j
public class GptSoVitsTtsService implements TtsService {

    private static final String PROVIDER_NAME = "gptsovits";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final int MAX_RETRY_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 600;

    /** 后处理默认滤镜：FFT 降噪 + 高通滤低频嗡声 + 响度归一化 */
    private static final String DEFAULT_POST_FILTER = "afftdn=nf=-30,highpass=f=70,loudnorm=I=-16:TP=-1.5";
    private static final String DEFAULT_REF_AUDIO = "D:\\xiaozhi\\.tools\\gptsovits-ref\\laoda-ref.wav";
    private static final String DEFAULT_API_URL = "http://127.0.0.1:9880";
    /** 后处理用的采样率：与 GPT-SoVITS 输出一致（AudioUtils 会再转 PCM） */
    private static final int POST_SAMPLE_RATE = 32000;
    private static final long POST_TIMEOUT_SECONDS = 60;

    private final String baseUrl;
    private final XiaozhiTtsOptions options;
    private final String outputPath;

    private final String refAudio;
    private final String promptLang;
    private final String textLang;
    /** 参考音频的文字稿。v3/v4 模型强制要求（留空会直接 400），v1/v2 可留空走"无提示模式" */
    private final String promptText;
    private final int sampleSteps;
    private final String postFilter;
    private final String ffmpegBin;

    public GptSoVitsTtsService(ConfigBO config, String voiceName, Double pitch, Double speed, String outputPath) {
        String apiUrl = config != null ? config.getApiUrl() : null;
        this.baseUrl = normalizeBaseUrl(apiUrl);
        this.options = XiaozhiTtsOptions.builder().voiceName(voiceName).pitch(pitch).speed(speed).build();
        this.outputPath = outputPath;

        this.refAudio = env("GPTSOVITS_REF_AUDIO", DEFAULT_REF_AUDIO);
        this.promptLang = env("GPTSOVITS_PROMPT_LANG", "en");
        this.textLang = env("GPTSOVITS_TEXT_LANG", "zh");
        this.promptText = env("GPTSOVITS_PROMPT_TEXT", "");
        // v4 换 BigVGAN 声码器后用 8 步就够（实测 RTF 0.76 能跟上实时；32 步要 1.72 会断音）
        this.sampleSteps = parseInt(env("GPTSOVITS_SAMPLE_STEPS", "8"), 8);
        this.postFilter = env("GPTSOVITS_POST_FILTER", DEFAULT_POST_FILTER);
        this.ffmpegBin = env("GPTSOVITS_FFMPEG", "ffmpeg");

        log.info("初始化 GPT-SoVITS TTS: apiUrl={}, refAudio={}, promptLang={}, textLang={}, 步数={}, 文字稿={}, 后处理={}, ffmpeg={}",
                baseUrl, refAudio, promptLang, textLang, sampleSteps,
                promptText.isEmpty() ? "空(v1/v2 可用)" : promptText.length() + " 字符",
                postFilter.isEmpty() ? "关闭" : "开启", ffmpegBin);

        if ("true".equalsIgnoreCase(env("GPTSOVITS_WARMUP", "true"))) {
            warmUp();
        }
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public XiaozhiTtsOptions getOptions() {
        return options;
    }

    /** GPT-SoVITS 返回 wav，AudioUtils.readAsPcm 支持 .wav */
    @Override
    public String audioFormat() {
        return "wav";
    }

    @Override
    public Path textToSpeech(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            throw new Exception("文本内容为空");
        }

        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            Path raw = Paths.get(outputPath, "gptsovits-raw-" + UUID.randomUUID().toString().replace("-", "") + ".wav");
            try {
                requestTts(text, raw);
                if (postFilter.isEmpty()) {
                    // 关闭后处理时，把临时名改成正式名，避免遗留 -raw- 文件
                    return renameToFinal(raw);
                }
                Path finalFile = postProcess(raw);
                // 只有真的产出了另一个文件才删原始文件；
                // postProcess 降级时会原样返回 raw，这时删掉就等于把音频弄丢了
                if (!finalFile.equals(raw)) {
                    deleteQuietly(raw);
                }
                return finalFile;
            } catch (Exception e) {
                lastException = e;
                deleteQuietly(raw);
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    log.warn("GPT-SoVITS 合成失败，重试 ({}/{}): {}", attempt, MAX_RETRY_ATTEMPTS, e.getMessage());
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                } else {
                    log.error("GPT-SoVITS 合成失败，已达最大重试次数", e);
                }
            }
        }
        throw lastException != null ? lastException : new Exception("GPT-SoVITS 语音合成失败");
    }

    /**
     * 调用 {@code POST /tts} 并把 wav 落到指定路径。
     * <p>语义与仓库自带用法一致：{@code prompt_text} 留空（无提示模式），
     * 实测比传 ASR 文本更稳 —— 参考音频的转写只要有错字，模型就会提前输出结束符。
     */
    private void requestTts(String text, Path outFile) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("text_lang", textLang);
        body.put("ref_audio_path", refAudio);
        body.put("prompt_text", promptText);
        body.put("prompt_lang", promptLang);
        body.put("text_split_method", "cut0");
        body.put("media_type", "wav");
        body.put("sample_steps", sampleSteps);
        // 角色里配的语速直接映射到 speed_factor（pitch 该接口不支持，已在文档中标注）
        body.put("speed_factor", getSpeed());

        Request request = new Request.Builder()
                .url(baseUrl + "/tts")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(JsonUtil.toJson(body), JSON))
                .build();

        try (var resp = HttpUtil.client.newCall(request).execute()) {
            byte[] data = resp.body() != null ? resp.body().bytes() : new byte[0];
            if (!resp.isSuccessful()) {
                throw new IOException("GPT-SoVITS 返回 " + resp.code() + ": " + preview(data));
            }
            // wav 头 44 字节，正常输出绝不会小于 1KB；偏小说明服务返回的是错误 JSON
            if (data.length < 1024 || !looksLikeWav(data)) {
                throw new IOException("GPT-SoVITS 返回内容不是有效 wav: " + preview(data));
            }
            Files.write(outFile, data);
        }
    }

    /**
     * ffmpeg 后处理：降噪 + 高通 + 响度归一化。
     * <p>失败时不让整条链路挂掉 —— 记警告并返回原始文件（宁可有点嘶声，也不能没声音）。
     * <p><b>两级落点</b>：先在输出目录里直接处理（正常部署走这条）；若因权限被拒（EACCES，典型于
     * 受限运行环境），改到临时目录处理后**复制**回输出目录。这样输出的文件名/相对路径始终不变，
     * 对外 URL（{@code /audio/xxx.wav}）也就始终是对的 —— 否则返回绝对路径会让前端拼不出播放地址。
     */
    private Path postProcess(Path raw) {
        Path finalFile = Paths.get(outputPath, getAudioFileName());

        // 先走临时目录：实测本机环境里【子进程】ffmpeg 往 audio\ 写会被拒（EACCES/Permission denied，
        // 绝对路径也拒），但 JVM 自己写没问题 —— 所以让 ffmpeg 在临时目录出成品，再由 JVM 复制回输出目录。
        // 这样输出文件名/相对路径始终不变，对外 URL（/audio/xxx.wav）也就始终是对的。
        Path tmpDir = resolveTmpDir();
        Path tmpOut = tmpDir.resolve(getAudioFileName());
        try {
            if (runFfmpeg(raw, tmpOut)) {
                Files.createDirectories(finalFile.getParent() == null ? Paths.get(".") : finalFile.getParent());
                Files.copy(tmpOut, finalFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return finalFile;
            }
        } catch (Exception e) {
            log.warn("GPT-SoVITS 后处理临时目录方案失败({})，改试输出目录", e.getMessage());
        } finally {
            deleteQuietly(tmpOut);
        }

        // 兜底：临时目录也不可用时，直接在输出目录里处理（正常部署走这条）
        if (runFfmpeg(raw, finalFile)) {
            return finalFile;
        }
        log.warn("GPT-SoVITS 后处理整体失败，返回未处理音频（宁可有点噪声，也不能没声音）");
        return raw;
    }

    /** 跑一次 ffmpeg；成功返回 true，失败把原因打进日志并清理半成品 */
    private boolean runFfmpeg(Path raw, Path out) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    ffmpegBin, "-y", "-i", raw.toString(),
                    "-af", postFilter,
                    "-ar", String.valueOf(POST_SAMPLE_RATE), "-ac", "1",
                    "-c:a", "pcm_s16le", out.toString(),
                    "-loglevel", "error")
                    .redirectErrorStream(true)   // 合并 stderr，便于失败时把原因打进日志
                    .start();
            // 必须把输出读干净：管道缓冲区满了会让子进程阻塞（表现为超时而非报错）
            String output = readAll(process.getInputStream());
            boolean finished = process.waitFor(POST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("GPT-SoVITS 后处理超时({}s)", POST_TIMEOUT_SECONDS);
                deleteQuietly(out);
                return false;
            }
            if (process.exitValue() != 0 || !Files.exists(out) || Files.size(out) < 1024) {
                // ffmpeg 的退出码是 AVERROR 值：-13=EACCES 权限拒绝、-2=ENOENT 找不到文件等
                log.warn("GPT-SoVITS 后处理失败(exit={}, out={}): {}",
                        process.exitValue(), out, output);
                deleteQuietly(out);
                return false;
            }
            return true;
        } catch (Exception e) {
            // ffmpeg 不在 PATH、进程启动失败等
            log.warn("GPT-SoVITS 后处理不可用({}), ffmpeg={}", e.getMessage(), ffmpegBin);
            if (process != null) {
                process.destroyForcibly();
            }
            deleteQuietly(out);
            return false;
        }
    }

    /**
     * 后处理临时目录。优先 {@code GPTSOVITS_TMP_DIR}，否则用系统临时目录。
     * 用于输出目录不可写时的兜底（受限运行环境下 ffmpeg 可能写不进项目目录）。
     */
    private Path resolveTmpDir() {
        String configured = env("GPTSOVITS_TMP_DIR", "");
        Path dir = configured.isEmpty()
                ? Paths.get(System.getProperty("java.io.tmpdir", "."), "gptsovits-post")
                : Paths.get(configured);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("创建后处理临时目录失败({})，退回系统临时目录", e.getMessage());
            dir = Paths.get(System.getProperty("java.io.tmpdir", "."));
        }
        return dir;
    }

    /** 读完子进程输出（不读会因管道缓冲满而卡死） */
    private static String readAll(java.io.InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() < 500) {
                    sb.append(line).append(' ');
                }
            }
        } catch (IOException ignored) {
            // 读流失败不影响主流程
        }
        return sb.toString().trim();
    }

    private Path renameToFinal(Path raw) {
        Path finalFile = Paths.get(outputPath, getAudioFileName());
        try {
            Files.move(raw, finalFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return finalFile;
        } catch (IOException e) {
            log.warn("重命名 TTS 输出失败，沿用原始文件名: {}", e.getMessage());
            return raw;
        }
    }

    /**
     * 预热：首次调用要把模型送上 GPU、让显卡从空闲低频率升上来，否则第一句要等 3~10 秒。
     * 这里只合成一个极短文本，失败也不影响服务可用（后台线程，不阻塞构造）。
     */
    private void warmUp() {
        Thread t = new Thread(() -> {
            long start = DateUtils.millis();
            try {
                Path tmp = Paths.get(outputPath, "gptsovits-warmup-" + UUID.randomUUID().toString().replace("-", "") + ".wav");
                requestTts("你好", tmp);
                deleteQuietly(tmp);
                log.info("GPT-SoVITS 预热完成，耗时 {} ms", DateUtils.millis() - start);
            } catch (Exception e) {
                log.warn("GPT-SoVITS 预热失败（不影响使用，首句会慢一些）: {}", e.getMessage());
            }
        }, "gptsovits-warmup");
        t.setDaemon(true);
        t.start();
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return DEFAULT_API_URL;
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String env(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v == null || v.trim().isEmpty()) ? defaultValue : v.trim();
    }

    private static int parseInt(String s, int defaultValue) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean looksLikeWav(byte[] data) {
        return data.length > 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F';
    }

    private static String preview(byte[] data) {
        String s = new String(data, 0, Math.min(data.length, 200), java.nio.charset.StandardCharsets.UTF_8);
        return s.replaceAll("\\s+", " ");
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响主流程
        }
    }
}