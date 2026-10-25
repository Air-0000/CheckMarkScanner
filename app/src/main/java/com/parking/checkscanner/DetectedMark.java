package com.parking.checkscanner;

import android.graphics.RectF;

public class DetectedMark {
    public int rowIndex;
    public int shiftIndex;
    public String name;
    public String plate;
    public String excelCell;
    public String shiftName;
    public RectF box;
    public float confidence;

    public DetectedMark(int rowIndex, int shiftIndex, String name, String plate, 
                        String excelCell, String shiftName, RectF box, float confidence) {
        this.rowIndex = rowIndex;
        this.shiftIndex = shiftIndex;
        this.name = name;
        this.plate = plate;
        this.excelCell = excelCell;
        this.shiftName = shiftName;
        this.box = box;
        this.confidence = confidence;
    }
}
