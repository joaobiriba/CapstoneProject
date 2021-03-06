package com.laquysoft.motivetto;

/**
 * Created by joaobiriba on 28/01/16.
 */

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

public class TileServer {

    private static final String LOG_TAG = TileServer.class.getSimpleName();

    public class TilePair {
        private final Bitmap bitmap;
        private final int seekTime;

        public TilePair(Bitmap aBitmap, int aSeekTime) {
            bitmap = aBitmap;
            seekTime = aSeekTime;
        }

        public Bitmap bitmap() {
            return bitmap;
        }

        public int seekTime() {
            return seekTime;
        }
    }

    Bitmap original, scaledImage, hard;
    int rows, columns, width, tileSize;
    HashSet<TilePair> slices;
    ArrayList<TilePair> unservedSlices;
    Random random;

    public TileServer(Bitmap original, Bitmap hard, int rows, int columns, int tileSize, boolean mode) {
        super();
        this.original = original;
        this.hard = hard;
        this.rows = rows;
        this.columns = columns;
        this.tileSize = tileSize;

        random = new Random();
        slices = new HashSet<TilePair>();
        sliceOriginal(mode);
    }

    protected void sliceOriginal(boolean mode) {
        int fullWidth = tileSize * rows;
        int fullHeight = tileSize * columns;

        int x, y;
        Bitmap bitmap;
        TilePair tilepair;
        for (int rowI = 0; rowI < 3; rowI++) {
            for (int colI = 0; colI < 3; colI++) {
                if (mode) {
                    scaledImage = Bitmap.createBitmap(hard);
                    y = 0;
                    x = 0;
                    bitmap = Bitmap.createScaledBitmap(scaledImage, tileSize, tileSize, true);
                } else {
                    scaledImage = Bitmap.createScaledBitmap(original, fullWidth, fullHeight, true);
                    x = rowI * tileSize;
                    y = colI * tileSize;
                    bitmap = Bitmap.createBitmap(scaledImage, y, x, tileSize, tileSize);
                }

                int seekTime = slices.size() * 3;

                //Debug Write seekTime on bitmap
                Canvas canvas = new Canvas(bitmap);

                Paint paint = new Paint();
                paint.setColor(Color.RED); // Text Color
                paint.setStrokeWidth(12); // Text Size
                paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)); // Text Overlapping Pattern
                // some more settings...

                if (!mode) {
                    canvas.drawBitmap(bitmap, 0, 0, paint);
                    canvas.drawText(Integer.toString(seekTime), 10, 10, paint);
                }

                Log.d(LOG_TAG, "sliceOriginal: " + seekTime);
                tilepair = new TilePair(bitmap, seekTime);
                slices.add(tilepair);
            }
        }
        unservedSlices = new ArrayList<TilePair>();
        unservedSlices.addAll(slices);
    }

    public TilePair serveRandomSlice() {
        if (unservedSlices.size() > 0) {
            int randomIndex = random.nextInt(unservedSlices.size());
            TilePair tilePair = unservedSlices.remove(randomIndex);
            return tilePair;
        }
        return null;
    }


}