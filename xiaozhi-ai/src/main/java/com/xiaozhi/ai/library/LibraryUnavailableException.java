package com.xiaozhi.ai.library;

/**
 * 图书馆数据不可用。
 *
 * <p>用于把"守护进程没起 / 未登录 / 网络超时 / 接口报错"这类技术失败，
 * 统一收敛成一句能直接说给用户听的话。
 *
 * <p><b>约定</b>：消息里只放中文人话，不放堆栈、不放 URL、不放 token。
 */
public class LibraryUnavailableException extends RuntimeException {

    public LibraryUnavailableException(String message) {
        super(message);
    }
}