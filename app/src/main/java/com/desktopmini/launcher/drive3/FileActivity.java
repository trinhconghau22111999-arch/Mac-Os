package com.desktopmini.launcher.drive3;

public class FileActivity extends DriveActivity {
    @Override protected String suffix() { return "tep"; }
    @Override protected String startUrl() { return "https://drive.google.com/drive/my-drive"; }
    @Override protected String label() { return "TỆP"; }
    @Override protected String pickType() { return "*/*"; }
    @Override protected int accent() { return 0xFF43A047; }
}
