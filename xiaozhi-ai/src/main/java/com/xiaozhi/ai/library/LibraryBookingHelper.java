package com.xiaozhi.ai.library;

import java.util.List;

/**
 * 预约记录的筛选与渲染，供取消/临时离开/签退三个 prepare 工具共用。
 *
 * <p>状态判断口径：
 * <ul>
 *   <li><b>可取消：status_name 含"预约成功"且未签到</b>。2026-11-24 实测修正：
 *       ① 预约成功状态码实测是 {@code status="9"}（凌晨文档误记为 2，数字码不可靠）；
 *       ② {@code member/seat} 的 {@code cancel} 字段同样不可靠（标 0 时学校网页
 *       仍可取消、当天第二次取消也放行，"每天限一次"并未在写接口强制）。
 *       所以按状态名 + 未签到进确认，最终由学校取消接口裁决——接口拒绝时
 *       把真实错误原样告诉用户。</li>
 *   <li>使用中/临时离开：按 {@code status_name} 中文匹配——这两种状态的码值还没实测到，
 *       用中文名比用数字稳（接口返回里 status_name 与 status 成对出现）</li>
 * </ul>
 */
final class LibraryBookingHelper {

    private LibraryBookingHelper() {}

    /** 单条判断：status_name 含"预约成功"且未签到 → 可尝试取消（最终由学校取消接口裁决） */
    static boolean cancellable(LibraryApiClient.Booking b) {
        return b.statusName() != null && b.statusName().contains("预约成功")
                && (b.signInTime() == null || b.signInTime().isBlank());
    }

    /** 当前可取消的记录（status_name=预约成功 且未签到） */
    static List<LibraryApiClient.Booking> cancellable(List<LibraryApiClient.Booking> all) {
        return all.stream().filter(LibraryBookingHelper::cancellable).toList();
    }

    /** 正在使用中的记录 */
    static List<LibraryApiClient.Booking> inUse(List<LibraryApiClient.Booking> all) {
        return all.stream().filter(LibraryApiClient.Booking::inUse).toList();
    }

    /** 使用中或临时离开（签退的适用范围） */
    static List<LibraryApiClient.Booking> active(List<LibraryApiClient.Booking> all) {
        return all.stream().filter(b -> b.inUse() || b.tempLeave()).toList();
    }

    /** 一条记录的摘要（反问/确认时给用户看） */
    static String describe(LibraryApiClient.Booking b) {
        return "「" + b.areaName() + "」" + b.day() + " " + b.start() + "-" + b.end()
                + "，" + b.seatNo() + " 号（" + (b.statusName() == null ? "状态未知" : b.statusName()) + "）";
    }

    /** 多条记录反问时的列表 */
    static String listForPick(List<LibraryApiClient.Booking> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("你现在有 ").append(list.size()).append(" 条相关记录：");
        for (int i = 0; i < Math.min(list.size(), 5); i++) {
            sb.append("\n  ").append(i + 1).append(". ").append(describe(list.get(i)));
        }
        if (list.size() > 5) {
            sb.append("\n  ……共 ").append(list.size()).append(" 条");
        }
        sb.append("\n想对哪一条操作？直接说序号或「区域+座位号」。");
        return sb.toString();
    }
}
