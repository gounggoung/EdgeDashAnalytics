package com.example.edgedashanalytics.model;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Parcel;

import androidx.annotation.NonNull;

@SuppressLint("ParcelCreator")
public class BitmapFrame extends Content{
    private final Bitmap frame;
    private final int frameIndex;




    public BitmapFrame(Bitmap frame, int frameIndex){
        super(frame.toString(), Integer.toString(frameIndex));
        this.frame = frame;
        this.frameIndex = frameIndex;
    }

    @Override
    public String toString(){
        return frameIndex + ": " + frame;
    }

    public int getFrameWidth() {
        return frame.getWidth();
    }

    public int getFrameHeight() {
        return frame.getHeight();
    }

    public Bitmap getFrame() {
        return frame;
    }

    public int getFrameIndex() {
        return frameIndex;
    }
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int i) {
            parcel.writeValue(frame);
            parcel.writeInt(frameIndex);
    }

    public boolean isInner() {
        return false;
    }
}
