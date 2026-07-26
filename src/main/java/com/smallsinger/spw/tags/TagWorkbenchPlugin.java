package com.smallsinger.spw.tags;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import javax.swing.*;
import javax.swing.filechooser.FileSystemView;

public final class TagWorkbenchPlugin extends SpwPlugin {
  private static ConfigHelper config;
  public TagWorkbenchPlugin(PluginContext context) { super(context); }

  @Override
  public void start() {
    config = WorkshopApi.manager().createConfigManager().getConfig(
        "tag-workbench.json");
    System.out.println("SPW 音乐标签工作台已加载");
  }

  @Override
  public void stop() {
    TagWorkbenchLauncher.close();
    CurrentMediaExtension.clear();
    MusicSources.clearCoverCache();
    config = null;
  }

  public static void openWorkbench() {
    try {
      TagWorkbenchLauncher.open();
    } catch (Throwable error) {
      error.printStackTrace();
      WorkshopApi.ui().toast("标签工作台打开失败：" + error.getMessage(),
                             WorkshopApi.Ui.ToastType.Error);
    }
  }

  static List<File> configuredMusicFolders() {
    try {
      Path state = libraryStateFile();
      if (Files.isRegularFile(state)) {
        List<File> folders = parseFolders(Files.readString(state));
        if (!folders.isEmpty())
          return folders;
      }
    } catch (Throwable ignored) {
    }
    try {
      if (config != null) {
        config.reload();
        String saved = config.get("library.folder", "");
        List<File> folders = parseFolders(saved);
        if (!folders.isEmpty()) {
          saveLibraryFolders(folders);
          return folders;
        }
      }
    } catch (Throwable ignored) {
    }
    return List.of();
  }

  static boolean skipCompleteTagsEnabled() {
    try {
      if (config != null) {
        config.reload();
        return config.get("matching.skip_complete", false);
      }
    } catch (Throwable ignored) {
    }
    return false;
  }

  private static Path libraryStateFile() {
    return Path.of(System.getenv("APPDATA"), "Salt Player for Windows",
                   "workshop", "data", "com.smallsinger.spw.tags",
                   "library-path.txt");
  }
  private static void saveLibraryFolders(List<File> folders) throws Exception {
    Path file = libraryStateFile();
    Files.createDirectories(file.getParent());
    String value = folders.stream()
                       .map(File::getAbsolutePath)
                       .distinct()
                       .reduce((a, b) -> a + System.lineSeparator() + b)
                       .orElse("");
    Files.writeString(file, value);
    if (config != null) {
      config.set("library.folder", value);
      config.save();
    }
  }
  private static List<File> parseFolders(String value) {
    LinkedHashSet<File> folders = new LinkedHashSet<>();
    if (value != null)
      for (String line : value.split("\\R")) {
        String path = line.trim();
        if (!path.isBlank())
          folders.add(new File(path));
      }
    return new ArrayList<>(folders);
  }

  public static void chooseLibraryFolder() {
    SwingUtilities.invokeLater(() -> {
      TagWorkbenchWindow.acquireLook();
      try {
      List<File> saved = configuredMusicFolders();
      DefaultListModel<String> pathModel = new DefaultListModel<>();
      for (File folder : saved)
        pathModel.addElement(folder.getAbsolutePath());
      JList<String> paths = new JList<>(pathModel);
      paths.setVisibleRowCount(6);
      paths.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
      paths.setFixedCellWidth(520);
      JButton select = new JButton("使用文件夹选择器添加目录");
      File iconTarget =
          !saved.isEmpty()
              ? saved.get(0)
              : FileSystemView.getFileSystemView().getHomeDirectory();
      select.setIcon(
          FileSystemView.getFileSystemView().getSystemIcon(iconTarget));
      select.setHorizontalAlignment(SwingConstants.LEFT);
      java.awt.event.ActionListener addFolder = event -> {
        JFileChooser chooser =
            new JFileChooser(FileSystemView.getFileSystemView());
        chooser.setDialogTitle("选择 SPW 音乐文件夹");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        List<File> current = new ArrayList<>();
        for (int i = 0; i < pathModel.size(); i++)
          current.add(new File(pathModel.get(i)));
        File initial =
            current.stream()
                .filter(File::isDirectory)
                .findFirst()
                .orElse(FileSystemView.getFileSystemView().getHomeDirectory());
        chooser.setCurrentDirectory(initial);
        chooser.setSelectedFile(initial);
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
          LinkedHashSet<File> merged = new LinkedHashSet<>(current);
          File[] selected = chooser.getSelectedFiles();
          if (selected.length == 0 && chooser.getSelectedFile() != null)
            selected = new File[] {chooser.getSelectedFile()};
          for (File folder : selected)
            if (folder.isDirectory())
              merged.add(folder);
          pathModel.clear();
          for (File folder : merged)
            pathModel.addElement(folder.getAbsolutePath());
          if (!pathModel.isEmpty())
            paths.setSelectedIndex(0);
        }
      };
      select.addActionListener(addFolder);
      JPanel panel = new JPanel(new java.awt.BorderLayout(0, 8));
      panel.add(select, java.awt.BorderLayout.NORTH);
      JScrollPane pathScroll = new RoundedPathScrollPane(paths);
      pathScroll.setBorder(BorderFactory.createTitledBorder(
          new RoundedDialogBorder(), "已保存路径"));
      pathScroll.getViewport().setOpaque(false);
      paths.setOpaque(false);
      panel.add(pathScroll, java.awt.BorderLayout.CENTER);
      JButton add = new JButton("添加");
      add.addActionListener(addFolder);
      JButton delete = new JButton("删除");
      Runnable deleteSelected = () -> {
        int[] selected = paths.getSelectedIndices();
        for (int i = selected.length - 1; i >= 0; i--)
          pathModel.remove(selected[i]);
      };
      delete.addActionListener(event -> deleteSelected.run());
      paths.getInputMap(JComponent.WHEN_FOCUSED).put(
          KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0),
          "delete-selected-paths");
      paths.getActionMap().put("delete-selected-paths",
                               new AbstractAction() {
        public void actionPerformed(java.awt.event.ActionEvent event) {
          deleteSelected.run();
        }
      });
      boolean[] accepted = {false};
      JDialog dialog = new JDialog((java.awt.Frame)null, "SPW 音乐文件夹", true);
      JButton confirm = new JButton("确认"), cancel = new JButton("取消");
      confirm.addActionListener(event -> {
        accepted[0] = true;
        dialog.dispose();
      });
      cancel.addActionListener(event -> dialog.dispose());
      JPanel rightButtons =
          new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
      rightButtons.add(confirm);
      rightButtons.add(cancel);
      JPanel bottom = new JPanel(new java.awt.BorderLayout());
      JPanel leftButtons =
          new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
      leftButtons.add(add);
      leftButtons.add(delete);
      bottom.add(leftButtons, java.awt.BorderLayout.WEST);
      bottom.add(rightButtons, java.awt.BorderLayout.EAST);
      JPanel dialogContent = new JPanel(new java.awt.BorderLayout(0, 10));
      dialogContent.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
      dialogContent.add(panel, java.awt.BorderLayout.CENTER);
      dialogContent.add(bottom, java.awt.BorderLayout.SOUTH);
      dialog.setContentPane(dialogContent);
      dialog.pack();
      dialog.setResizable(false);
      dialog.setLocationRelativeTo(null);
      dialog.setVisible(true);
      try {
        if (accepted[0]) {
          List<File> folders = new ArrayList<>();
          for (int i = 0; i < pathModel.size(); i++)
            folders.add(new File(pathModel.get(i)));
          try {
            saveLibraryFolders(folders);
            WorkshopApi.ui().toast(
                "已保存 " + folders.size() + " 个音乐文件夹",
                WorkshopApi.Ui.ToastType.Success);
          } catch (Exception error) {
            WorkshopApi.ui().toast(
                "音乐文件夹保存失败：" + error.getMessage(),
                WorkshopApi.Ui.ToastType.Error);
          }
        }
      } finally {
        SmoothScrollSupport.disposeTree(dialog);
        paths.setModel(new DefaultListModel<>());
        dialog.setContentPane(new JPanel());
        dialog.dispose();
      }
      } finally {
        TagWorkbenchWindow.releaseLook();
      }
    });
  }

  public static void openSourcePage() {
    try {
      Desktop.getDesktop().browse(
          URI.create("https://github.com/univers629/SPW-Tag-Workbench"));
    } catch (Exception error) {
      WorkshopApi.ui().toast("无法打开开源页面：" + error.getMessage(),
                             WorkshopApi.Ui.ToastType.Error);
    }
  }

  private static final class RoundedPathScrollPane extends JScrollPane {
    RoundedPathScrollPane(JComponent view) {
      super(view);
      setOpaque(false);
      putClientProperty("JComponent.roundRect", true);
      getVerticalScrollBar().setOpaque(false);
      getVerticalScrollBar().putClientProperty("JComponent.roundRect", true);
      getVerticalScrollBar().setUnitIncrement(12);
      SmoothScrollSupport.install(this);
    }
    public void paint(java.awt.Graphics graphics) {
      java.awt.Graphics2D g = (java.awt.Graphics2D)graphics.create();
      g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                         java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
      g.clip(new java.awt.geom.RoundRectangle2D.Float(
          0, 0, getWidth(), getHeight(), 14, 14));
      g.setColor(UIManager.getColor("TextArea.background"));
      g.fillRect(0, 0, getWidth(), getHeight());
      super.paint(g);
      g.dispose();
    }
  }

  private static final class RoundedDialogBorder
      extends javax.swing.border.AbstractBorder {
    public void paintBorder(java.awt.Component component,
                            java.awt.Graphics graphics, int x, int y,
                            int width, int height) {
      java.awt.Graphics2D g = (java.awt.Graphics2D)graphics.create();
      g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                         java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
      java.awt.Color color = UIManager.getColor("Component.borderColor");
      g.setColor(color == null ? new java.awt.Color(90, 90, 90) : color);
      g.drawRoundRect(x, y, width - 1, height - 1, 14, 14);
      g.dispose();
    }
    public java.awt.Insets getBorderInsets(java.awt.Component component) {
      return new java.awt.Insets(8, 8, 8, 8);
    }
  }
}
