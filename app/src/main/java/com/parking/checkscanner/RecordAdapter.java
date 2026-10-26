package com.parking.checkscanner;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class RecordAdapter extends RecyclerView.Adapter<RecordAdapter.ViewHolder> {

    // 班次格与摘要使用窄体字，与统计数字保持一致
    private static final Typeface CONDENSED = Typeface.create("sans-serif-condensed", Typeface.NORMAL);

    public interface OnItemClickListener {
        void onItemClick(ParkingRecord record, int position);
        void onShiftToggled(ParkingRecord record, int shiftIndex);
    }

    private List<ParkingRecord> allRecords = new ArrayList<>();
    private List<ParkingRecord> displayList = new ArrayList<>();
    private OnItemClickListener listener;

    public RecordAdapter(List<ParkingRecord> records, OnItemClickListener listener) {
        this.listener = listener;
        setRecords(records);
    }

    public void setRecords(List<ParkingRecord> records) {
        this.allRecords = records != null ? records : new ArrayList<>();
        this.displayList = new ArrayList<>(this.allRecords);
        notifyDataSetChanged();
    }

    public void filter(String query) {
        displayList.clear();
        if (query == null || query.trim().isEmpty()) {
            displayList.addAll(allRecords);
        } else {
            String q = query.trim().toLowerCase();
            for (ParkingRecord r : allRecords) {
                if (r.getName().toLowerCase().contains(q) ||
                    r.getPlateNumber().toLowerCase().contains(q) ||
                    String.valueOf(r.getIndex()).equals(q)) {
                    displayList.add(r);
                }
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_parking_record, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ParkingRecord record = displayList.get(position);
        holder.bind(record);
    }

    @Override
    public int getItemCount() {
        return displayList.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvIndex, tvName, tvPlate, tvSummary, tvExcelRow;
        GridLayout gridShifts;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvIndex = itemView.findViewById(R.id.tv_index);
            tvName = itemView.findViewById(R.id.tv_name);
            tvPlate = itemView.findViewById(R.id.tv_plate);
            tvSummary = itemView.findViewById(R.id.tv_summary);
            tvExcelRow = itemView.findViewById(R.id.tv_excel_row);
            gridShifts = itemView.findViewById(R.id.grid_shifts);

            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    listener.onItemClick(displayList.get(pos), pos);
                }
            });
        }

        void bind(ParkingRecord record) {
            tvIndex.setText(String.valueOf(record.getIndex()));
            tvName.setText(record.getName());
            tvPlate.setText(record.getPlateNumber());
            tvExcelRow.setText("Excel 第" + record.getExcelRow() + "行");

            Context context = itemView.getContext();
            if (record.hasAnyChecked()) {
                tvSummary.setText("已打钩: " + record.getCheckedSummary());
                tvSummary.setTextColor(ContextCompat.getColor(context, R.color.text_main));
                tvSummary.setTypeface(null, Typeface.BOLD);
            } else {
                tvSummary.setText("未打钩");
                tvSummary.setTextColor(ContextCompat.getColor(context, R.color.text_hint));
                tvSummary.setTypeface(null, Typeface.NORMAL);
            }

            // 填充 14 个班次按钮
            gridShifts.removeAllViews();
            int totalShifts = 14;
            for (int i = 0; i < totalShifts; i++) {
                final int shiftIdx = i;
                TextView chip = new TextView(itemView.getContext());
                String cell = record.getExcelCell(shiftIdx);
                String sub = (shiftIdx % 2 == 0) ? "白" : "夜";
                String dayShort = ShiftConstants.DAYS[shiftIdx / 2].substring(2); // "一", "二"...

                boolean isChecked = record.isShiftChecked(shiftIdx);

                if (isChecked) {
                    chip.setText(dayShort + sub + "√\n" + cell);
                    chip.setBackgroundResource(R.drawable.bg_chip_checked);
                    chip.setTextColor(ContextCompat.getColor(context, R.color.on_hazard));
                    chip.setTypeface(CONDENSED, Typeface.BOLD);
                } else {
                    chip.setText(dayShort + sub + "\n" + cell);
                    chip.setBackgroundResource(R.drawable.bg_chip_normal);
                    chip.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
                    chip.setTypeface(CONDENSED, Typeface.NORMAL);
                }

                chip.setTextSize(10f);
                chip.setGravity(android.view.Gravity.CENTER);
                chip.setPadding(4, 6, 4, 6);

                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = 0;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                lp.setMargins(2, 2, 2, 2);
                chip.setLayoutParams(lp);

                chip.setOnClickListener(v -> {
                    record.toggleShift(shiftIdx);
                    notifyItemChanged(getAdapterPosition());
                    if (listener != null) {
                        listener.onShiftToggled(record, shiftIdx);
                    }
                });

                gridShifts.addView(chip);
            }
        }
    }
}
