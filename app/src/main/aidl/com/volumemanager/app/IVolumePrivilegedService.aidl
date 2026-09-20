package com.volumemanager.app;

import com.volumemanager.app.PlaybackAppInfo;
import com.volumemanager.app.IPlaybackChangeListener;
import android.os.Bundle;

interface IVolumePrivilegedService {
    void destroy() = 16777114;

    List<PlaybackAppInfo> listActivePlaybacks() = 1;
    void setPackageVolume(String packageName, float volume) = 2;
    void applyStoredVolumes(in Bundle storedVolumes) = 3;
    void registerPlaybackWatcher() = 4;
    void unregisterPlaybackWatcher() = 5;
    void setPlaybackChangeListener(IPlaybackChangeListener listener) = 6;
}
