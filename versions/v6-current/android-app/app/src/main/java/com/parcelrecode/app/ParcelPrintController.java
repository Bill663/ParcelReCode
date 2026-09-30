package com.parcelrecode.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.print.PrintAttributes;
import android.print.PrintManager;

final class ParcelPrintController {
    static final class PrintLabel {
        final Bitmap qrBitmap;
        final String tracking;
        final String payload;

        PrintLabel(Bitmap qrBitmap, String tracking, String payload) {
            this.qrBitmap = qrBitmap;
            this.tracking = tracking == null ? "" : tracking;
            this.payload = payload;
        }

        boolean isValid() {
            return qrBitmap != null && payload != null && !payload.trim().isEmpty();
        }
    }

    boolean print(Context context, boolean dual4x6, PrintLabel topLabel, PrintLabel bottomLabel) {
        if (topLabel == null || !topLabel.isValid()) return false;
        if (dual4x6 && (bottomLabel == null || !bottomLabel.isValid())) return false;

        PrintManager manager = (PrintManager) context.getSystemService(Context.PRINT_SERVICE);
        if (manager == null) return false;

        PrintAttributes.MediaSize size = new PrintAttributes.MediaSize(
                dual4x6 ? "PARCEL_4X6" : "PARCEL_2X2",
                context.getString(dual4x6 ? R.string.label_media_name_4x6 : R.string.label_media_name),
                dual4x6 ? 4000 : 2000,
                dual4x6 ? 6000 : 2000
        );
        PrintAttributes attributes = new PrintAttributes.Builder()
                .setMediaSize(size)
                .setResolution(new PrintAttributes.Resolution(
                        "parcel_300",
                        context.getString(R.string.print_resolution_name),
                        300,
                        300
                ))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                .build();
        LabelPrintAdapter.LayoutMode layoutMode = dual4x6
                ? LabelPrintAdapter.LayoutMode.DUAL_4X6
                : LabelPrintAdapter.LayoutMode.SINGLE_2X2;
        PrintLabel bottom = dual4x6 ? bottomLabel : topLabel;
        manager.print(
                context.getString(R.string.print_job_name, dual4x6 ? "" : topLabel.tracking),
                new LabelPrintAdapter(
                        context,
                        topLabel.qrBitmap,
                        dual4x6 ? "" : topLabel.tracking,
                        topLabel.payload,
                        bottom.qrBitmap,
                        bottom.payload,
                        layoutMode),
                attributes
        );
        return true;
    }
}
