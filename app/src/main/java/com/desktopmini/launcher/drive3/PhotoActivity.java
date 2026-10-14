package com.desktopmini.launcher.drive3;

public class PhotoActivity extends DriveActivity {
    @Override protected String suffix() { return "anh"; }
    @Override protected String startUrl() { return "https://drive.google.com/drive/my-drive"; }
    @Override protected String label() { return "ẢNH"; }
    @Override protected String pickType() { return "image/*"; }
    @Override protected int accent() { return 0xFF1E88E5; }
}
