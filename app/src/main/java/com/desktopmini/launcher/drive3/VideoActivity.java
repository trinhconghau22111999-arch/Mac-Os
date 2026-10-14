package com.desktopmini.launcher.drive3;

public class VideoActivity extends DriveActivity {
    @Override protected String suffix() { return "video"; }
    @Override protected String startUrl() { return "https://drive.google.com/drive/my-drive"; }
    @Override protected String label() { return "VIDEO"; }
    @Override protected String pickType() { return "video/*"; }
    @Override protected int accent() { return 0xFFE53935; }
}
