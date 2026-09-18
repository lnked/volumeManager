package com.volumemanager.app

import android.os.Parcel
import android.os.Parcelable

data class PlaybackAppInfo(
    val packageName: String,
    val uid: Int,
    val label: String,
    val playerCount: Int,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        packageName = parcel.readString().orEmpty(),
        uid = parcel.readInt(),
        label = parcel.readString().orEmpty(),
        playerCount = parcel.readInt(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(packageName)
        parcel.writeInt(uid)
        parcel.writeString(label)
        parcel.writeInt(playerCount)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<PlaybackAppInfo> {
        override fun createFromParcel(parcel: Parcel): PlaybackAppInfo = PlaybackAppInfo(parcel)
        override fun newArray(size: Int): Array<PlaybackAppInfo?> = arrayOfNulls(size)
    }
}
