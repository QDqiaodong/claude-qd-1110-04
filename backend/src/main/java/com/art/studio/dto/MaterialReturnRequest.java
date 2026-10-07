package com.art.studio.dto;

import java.time.LocalDate;

/** 画材归还请求：只能针对原领用记录做一次或多次归还。 */
public class MaterialReturnRequest {

    public Integer qty;
    public LocalDate returnDate;
}
