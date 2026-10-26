package com.platform.knowledge.service;

import java.util.List;

/**
 * PDF 中一个表格块及其页面位置（坐标为 PDFBox 的 top-down 坐标）。
 *
 * <p>{@code spans} 记录表格占用的每一页区域，用于把同一区域的文本行从正文流中剔除，
 * 避免“表格文字既在正文又出现在重建表格里”。跨页表格只在第一页插入一次。</p>
 *
 * @param mergeable 是否允许由布局层做跨页合并；矩形/线条表格为 true，文本坐标表格已在
 *                  提取器内部完成跨页合并，避免被二次误合并
 */
record PdfTableBlock(int pageFrom, int pageTo, float top, float bottom,
                     String html, List<PageSpan> spans, boolean mergeable) {

    record PageSpan(int page, float top, float bottom, float left, float right) {
    }

    static PdfTableBlock singlePage(int page, float top, float bottom,
                                    float left, float right, String html, boolean mergeable) {
        return new PdfTableBlock(page, page, top, bottom, html,
                List.of(new PageSpan(page, top, bottom, left, right)), mergeable);
    }
}
