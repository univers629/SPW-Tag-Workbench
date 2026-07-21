package com.smallsinger.spw.tags;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import javax.swing.*;
import javax.swing.filechooser.FileSystemView;
import java.io.File;
import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TagWorkbenchPlugin extends SpwPlugin {
    private static ConfigHelper config;
    public TagWorkbenchPlugin(PluginContext context) {
        super(context);
    }

    @Override
    public void start() {
        config = WorkshopApi.manager().createConfigManager().getConfig("tag-workbench.json");
        System.out.println("SPW 音乐标签工作台已加载");
    }

    @Override
    public void stop() {
        TagWorkbenchLauncher.close();
        config = null;
    }

    public static void openWorkbench() {
        try {
            TagWorkbenchLauncher.open();
        } catch (Throwable error) {
            error.printStackTrace();
            WorkshopApi.ui().toast("标签工作台打开失败：" + error.getMessage(), WorkshopApi.Ui.ToastType.Error);
        }
    }

    static File configuredMusicFolder() {
        try { Path state = libraryStateFile(); if (Files.isRegularFile(state)) { String saved = Files.readString(state).trim(); if (!saved.isBlank()) return new File(saved); } } catch (Throwable ignored) {}
        try { if (config == null) return null; config.reload(); String path = config.get("library.folder", ""); if (path != null && !path.isBlank()) { saveLibraryFolder(new File(path)); return new File(path); } }
        catch (Throwable ignored) {} return null;
    }

    private static Path libraryStateFile() { return Path.of(System.getenv("APPDATA"), "Salt Player for Windows", "workshop", "data", "com.smallsinger.spw.tags", "library-path.txt"); }
    private static void saveLibraryFolder(File folder) throws Exception { Path file = libraryStateFile(); Files.createDirectories(file.getParent()); Files.writeString(file, folder.getAbsolutePath()); }

    public static void chooseLibraryFolder() {
        SwingUtilities.invokeLater(() -> {
            JFileChooser chooser = new JFileChooser(FileSystemView.getFileSystemView());
            chooser.setDialogTitle("选择 SPW 音乐文件夹"); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            File[] roots = FileSystemView.getFileSystemView().getRoots(); if (roots.length > 0) chooser.setCurrentDirectory(roots[0]);
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                try { File folder=chooser.getSelectedFile();saveLibraryFolder(folder);if(config!=null){config.set("library.folder",folder.getAbsolutePath());config.save();}WorkshopApi.ui().toast("音乐文件夹已保存："+folder.getAbsolutePath(), WorkshopApi.Ui.ToastType.Success); }
                catch(Exception error){WorkshopApi.ui().toast("音乐文件夹保存失败："+error.getMessage(),WorkshopApi.Ui.ToastType.Error);}
            }
        });
    }

    public static void openSourcePage() {
        try {
            Desktop.getDesktop().browse(URI.create("https://github.com/univers629/SPW-Tag-Workbench"));
        } catch (Exception error) {
            WorkshopApi.ui().toast("无法打开开源页面：" + error.getMessage(), WorkshopApi.Ui.ToastType.Error);
        }
    }
}
