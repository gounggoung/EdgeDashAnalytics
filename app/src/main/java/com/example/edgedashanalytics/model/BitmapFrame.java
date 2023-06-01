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
        super("frame", "frame");
        this.frame = frame;
        this.frameIndex = frameIndex;
    }

    @Override
    public String toString(){
        return frameIndex + ": " + frame;
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
}
