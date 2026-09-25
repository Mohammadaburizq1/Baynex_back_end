package com.byonix.shoplink.service;

import com.byonix.shoplink.repository.OrderExportProjection;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * RFC 4180-style CSV: every field quoted, embedded quotes doubled, CRLF records, UTF-8 with a BOM
 * (without it Excel reads non-Latin customer/product names as mojibake). Free-text fields are
 * neutralized against spreadsheet formula injection.
 */
final class ReportCsv {
    private ReportCsv() {}

    static final String ORDERS_HEADER = "order_code,created_at_utc,status,fulfillment_method,customer_name,"
            + "subtotal,discount,delivery_fee,total,currency";

    static byte[] orders(List<OrderExportProjection> rows) {
        StringBuilder csv = new StringBuilder("﻿").append(ORDERS_HEADER).append("\r\n");
        for (OrderExportProjection row : rows) {
            csv.append(Stream.of(row.getOrderCode(), row.getCreatedAt(), row.getStatus(), row.getDeliveryMethod(),
                            row.getCustomerName(), row.getSubtotal(), row.getDiscount(), row.getDeliveryFee(),
                            row.getTotal(), row.getCurrency())
                    .map(ReportCsv::cell).collect(Collectors.joining(","))).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // null → empty field (e.g. the unknown currency of a pre-M1-02 order; never a guessed value).
    static String cell(Object value) {
        if (value == null) return "\"\"";
        String text = value instanceof BigDecimal amount ? amount.toPlainString() : value.toString();
        if (value instanceof String) {
            // A spreadsheet evaluates a cell starting with = + - @ (or tab/CR) as a formula, even
            // after leading whitespace/control characters, so prefix those with an apostrophe.
            int first = 0;
            while (first < text.length() && (Character.isWhitespace(text.charAt(first))
                    || Character.isSpaceChar(text.charAt(first)) || Character.isISOControl(text.charAt(first))
                    || text.charAt(first) == '﻿')) first++;
            if ((!text.isEmpty() && (text.charAt(0) == '\t' || text.charAt(0) == '\r' || text.charAt(0) == '\n'))
                    || (first < text.length() && "=+-@".indexOf(text.charAt(first)) >= 0)) text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
