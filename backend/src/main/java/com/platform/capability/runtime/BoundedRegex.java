package com.platform.capability.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 用户自定义正则的限时执行：灾难性回溯（如 (a+)+$）与深递归导致的
 * StackOverflowError 都不得拖死请求线程——工具输出是模型/外部内容，长度不可控。
 * 超时或异常时返回原输入，由调用方按"该规则不适用"继续。
 */
public final class BoundedRegex {

    private static final ExecutorService WATCHDOG = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "bounded-regex");
        t.setDaemon(true);
        return t;
    });

    private BoundedRegex() {
    }

    public static String replaceAll(String input, Pattern pattern, String replacement, long timeoutMs) {
        if (input == null || input.isEmpty()) return input;
        Future<String> future = WATCHDOG.submit(
                () -> pattern.matcher(new InterruptibleCharSequence(input)).replaceAll(replacement));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return input;
        } catch (ExecutionException e) {
            // StackOverflowError 等 Throwable 都按“规则不适用”处理
            return input;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return input;
        }
    }

    /** matcher 每读一个字符都检查中断，让 cancel(true) 能真正打断卡在回溯里的线程。 */
    static final class InterruptibleCharSequence implements CharSequence {
        private final CharSequence inner;

        InterruptibleCharSequence(CharSequence inner) {
            this.inner = inner;
        }

        @Override
        public char charAt(int index) {
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException("regex interrupted");
            }
            return inner.charAt(index);
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new InterruptibleCharSequence(inner.subSequence(start, end));
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
