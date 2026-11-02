package com.parking.checkscanner;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.EditText;
import android.view.View;
import android.view.ViewGroup;
import androidx.appcompat.app.AlertDialog;
import java.lang.reflect.*;
import java.util.*;

public class SerialExportTest extends Instrumentation {
    @Override public void onCreate(android.os.Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        android.os.Bundle result=new android.os.Bundle();
        try {
            testSyntheticSerialColumn();
            try { testScreenshotSerialColumn(); } catch(java.io.FileNotFoundException missingPrivateFixture) { android.util.Log.i("SerialExportTest","Private screenshot fixture not installed"); }
            testUnknownPagesCannotExportAndConfirmationPreservesMarks();
            result.putString("stream","PASS: screenshot OCR, page gates, dialog confirmation, mark preservation, merged export");
            finish(-1,result);
        } catch(Throwable e) {
            android.util.Log.e("SerialExportTest","FAILED",e);
            result.putString("stream",android.util.Log.getStackTraceString(e));finish(0,result);
        }
    }
    private Instrumentation getInstrumentation() {return this;}
    private void runTestOnUiThread(Runnable task) {runOnMainSync(task);}
    private static void assertTrue(boolean b) {assertTrue("assertion failed",b);}
    private static void assertTrue(String message,boolean b) {if(!b)throw new AssertionError(message);}
    private static void assertFalse(String message,boolean b) {assertTrue(message,!b);}
    private static void assertNotNull(Object value) {assertTrue(value!=null);}
    private static void assertEquals(Object a,Object b) {assertTrue("Expected "+a+" got "+b,a.equals(b));}
    private static Object call(Object obj, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = obj.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(obj,args);
    }
    private android.view.accessibility.AccessibilityNodeInfo findEdit(android.view.accessibility.AccessibilityNodeInfo n) {
        if(n==null)return null;
        if("android.widget.EditText".contentEquals(n.getClassName()))return n;
        for(int i=0;i<n.getChildCount();i++) {android.view.accessibility.AccessibilityNodeInfo found=findEdit(n.getChild(i));if(found!=null)return found;}
        return null;
    }
    private void click(String text) {
        List<android.view.accessibility.AccessibilityNodeInfo> nodes=getInstrumentation().getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText(text);
        assertFalse("Missing button "+text,nodes.isEmpty());
        assertTrue(nodes.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
        android.os.SystemClock.sleep(400);
    }
    public void testSyntheticSerialColumn() throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(1000,1200,Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE);
        android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(android.graphics.Color.BLACK);paint.setTextSize(28);
        CheckmarkDetector.TableGrid grid=new CheckmarkDetector.TableGrid();grid.shiftsX1=360;
        for(int i=0;i<=20;i++)grid.hLines.add(100+i*50);
        for(int i=0;i<20;i++)canvas.drawText(String.valueOf(116+i),60,135+i*50,paint);
        Method method=TableOcrScanner.class.getDeclaredMethod("retrySerialColumn",com.google.mlkit.vision.text.TextRecognizer.class,Bitmap.class,CheckmarkDetector.TableGrid.class,int.class);
        method.setAccessible(true);
        com.google.mlkit.vision.text.TextRecognizer reader=com.google.mlkit.vision.text.TextRecognition.getClient(new com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build());
        try { assertEquals(116,method.invoke(null,reader,bitmap,grid,20)); } finally {reader.close();bitmap.recycle();}
    }
    public void testScreenshotSerialColumn() throws Exception {
        Bitmap bitmap;
        try(java.io.InputStream in=getContext().getAssets().open("serial-116.jpg")) { bitmap=BitmapFactory.decodeStream(in); }
        List<ParkingRecord> fallback = new ArrayList<>();
        for(int i=0;i<39;i++) fallback.add(new ParkingRecord(i+1,"人员"+(i+1),""));
        CheckmarkDetector.DetectionResult detected = CheckmarkDetector.detect(bitmap,fallback,1f,0,0,false,false);
        assertNotNull(detected);
        TableOcrScanner.OcrResult ocr = TableOcrScanner.scanTable(bitmap,detected.grid,detected.records);
        android.util.Log.i("SerialExportTest", "screenshot start="+ocr.detectedStartIdx+" rows="+ocr.records.size());
        // A partial screenshot may remain unresolved, but must never be silently assigned another person's range.
        assertEquals(116,ocr.detectedStartIdx);
    }
    public void testUnknownPagesCannotExportAndConfirmationPreservesMarks() throws Throwable {
        getTargetContext().getSharedPreferences("app_mode",0).edit().putBoolean("waste",false).commit();
        MainActivity activity = (MainActivity)getInstrumentation().startActivitySync(
            new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            runTestOnUiThread(() -> {
                try {
                    Field f=MainActivity.class.getDeclaredField("pageItems");f.setAccessible(true);
                    MainActivity.PageItem[] pages=(MainActivity.PageItem[])f.get(activity);
                    for(int slot: new int[]{0,3}) {
                        MainActivity.PageItem p=pages[slot];
                        p.originalBitmap=Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888); p.fromUser=true;
                        p.records.get(1).setShiftChecked(3,true);
                        assertEquals(false,call(activity,"isExportPage",new Class[]{int.class},slot));
                    }
                    call(activity,"confirmPageNumber",new Class[]{int.class,boolean.class},3,false);
                } catch(Exception e) {throw new RuntimeException(e);}
            });
            getInstrumentation().waitForIdleSync();
            android.view.accessibility.AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
            android.view.accessibility.AccessibilityNodeInfo edit = findEdit(root);
            for(int attempt=0; edit==null && attempt<30; attempt++) {
                android.os.SystemClock.sleep(100);
                edit=findEdit(getInstrumentation().getUiAutomation().getRootInActiveWindow());
            }
            if(edit==null) {
                android.util.Log.e("SerialExportTest","Root="+getInstrumentation().getUiAutomation().getRootInActiveWindow());
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),"dialog-test.png"))) {
                    getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,out);
                }
            }
            assertNotNull(edit);
            android.os.Bundle values = new android.os.Bundle();
            values.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"116");
            assertTrue(edit.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,values));
            click("预览范围");
            getInstrumentation().waitForIdleSync();
            click("一致，确认");
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> {
                try {
                    Field f=MainActivity.class.getDeclaredField("pageItems");f.setAccessible(true);
                    MainActivity.PageItem p=((MainActivity.PageItem[])f.get(activity))[3];
                    assertEquals(116,p.records.get(0).getIndex());
                    assertEquals(154,p.records.get(38).getIndex());
                    assertTrue(p.records.get(1).isShiftChecked(3));
                    assertEquals(true,call(activity,"isExportPage",new Class[]{int.class},3));
                    List<ParkingRecord> merged=(List<ParkingRecord>)call(activity,"getExportRecords",new Class[]{});
                    boolean found=false;
                    for(ParkingRecord r:merged) if(r.getIndex()==117) {assertTrue(r.isShiftChecked(3));found=true;}
                    assertTrue(found);
                    ExcelTemplateFiller.FillResult output=ExcelTemplateFiller.fillBuiltInTemplate(activity,merged);
                    assertTrue(output.message,output.success);
                    assertEquals(1,output.filledMarksCount);
                    assertTrue(output.outputFile.length()>0);
                    android.util.Log.i("SerialExportTest","Exported "+output.outputFile);
                } catch(Exception e) {throw new RuntimeException(e);}
            });
        } finally { runTestOnUiThread(() -> activity.finish()); }
    }
}
