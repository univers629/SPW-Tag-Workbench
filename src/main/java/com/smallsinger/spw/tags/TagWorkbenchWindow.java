package com.smallsinger.spw.tags;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.filechooser.FileSystemView;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.JTextComponent;

final class TagWorkbenchWindow extends JFrame {
  private static final Color ACCENT = new Color(35, 109, 246),
                             SUB = new Color(102, 110, 122);
  private static final String[] FONT_UI_KEYS = {
      "Label.font", "Button.font", "ToggleButton.font", "CheckBox.font",
      "RadioButton.font", "ComboBox.font", "Menu.font", "MenuItem.font",
      "CheckBoxMenuItem.font", "RadioButtonMenuItem.font", "TextField.font",
      "FormattedTextField.font", "PasswordField.font", "TextArea.font",
      "TextPane.font", "EditorPane.font", "Table.font", "TableHeader.font",
      "List.font", "Tree.font", "ToolTip.font", "Spinner.font",
      "TitledBorder.font", "ProgressBar.font"
  };
  private static final String[] TEXT_UI_KEYS = {
      "Label.foreground", "Button.foreground", "ToggleButton.foreground",
      "CheckBox.foreground", "RadioButton.foreground", "TextField.foreground",
      "FormattedTextField.foreground", "PasswordField.foreground",
      "TextArea.foreground", "TextPane.foreground", "EditorPane.foreground",
      "ComboBox.foreground", "List.foreground", "Table.foreground",
      "TableHeader.foreground", "Menu.foreground", "MenuItem.foreground",
      "CheckBoxMenuItem.foreground", "RadioButtonMenuItem.foreground",
      "ToolTip.foreground", "Spinner.foreground", "TitledBorder.titleColor"
  };
  private static volatile Font resolvedUiFont;
  private final SongModel model = new SongModel();
  private final JTable table = new RoundedTable(model);
  private final TableRowSorter<SongModel> sorter = new TableRowSorter<>(model);
  private final JTextField listSearch = input();
  private final CoverView cover = new CoverView();
  private final JLabel coverInfo = new JLabel("", SwingConstants.CENTER);
  private final JTextField title = input(), artist = input(), album = input(),
                           albumArtist = input();
  private final JTextField lyricist = input(), composer = input(),
                           year = input(), track = input(), disc = input(),
                           genre = input();
  private final JTextArea lyrics = new PlaceholderTextArea(
                              "右键可单独匹配歌词源", 8),
                          focusedLyrics = new PlaceholderTextArea(
                              "右键可单独匹配歌词源", 12),
                          comment = textArea(3);
  private final JLabel status = new JLabel("请选择音频文件开始");
  private final JTextPane logArea = new JTextPane();
  private final JLabel logHint = new JLabel("up", SwingConstants.CENTER);
  private Timer logAnimation;
  private boolean logExpanded;
  private JScrollPane logScroll;
  private JPanel logFooter;
  private final JButton listExpandButton =
      button("展开", this::toggleListExpansion, false);
  private JButton spectrogramButton;
  private final JLabel topHint = new JLabel(
      "小提示：在三个区域点右键，就能分别匹配全部标签、基础标签或歌词",
      SwingConstants.CENTER);
  private final Timer tipTimer;
  private JScrollPane metadataScroll;
  private JScrollPane lyricScroll;
  private JScrollPane focusedLyricScroll;
  private final JComboBox<String> lyricFormatBox =
      new JComboBox<>(new String[] {"LRC（逐字）", "LRC（逐行）",
                                    "LRC（ESLyric）", "SRT", "ASS",
                                    "纯文本"});
  private final JCheckBox lyricOriginal = new CompactCheckBox("原文", true),
      lyricTranslation = new CompactCheckBox("译文", true),
      lyricRomanization = new CompactCheckBox("罗马音", false);
  private final JSpinner lyricOffset =
      new JSpinner(new SpinnerNumberModel(0, -60000, 60000, 100));
  private String rawFocusedLyrics = "";
  private boolean updatingLyricSettings;
  private final LyricCandidateModel lyricCandidateModel =
      new LyricCandidateModel();
  private final JTable lyricCandidateTable =
      new RoundedTable(lyricCandidateModel);
  private final FadingOverlayButton lyricsToggleButton =
      new FadingOverlayButton("展开", this::toggleLyricsFocus);
  private OverlayButtonHost workOverlayHost;
  private final JComboBox<MusicSource> sourceBox =
      new JComboBox<>(MusicSources.ALL.toArray(MusicSource[] ::new));
  private final JCheckBox followPlayback = new JCheckBox("跟随播放", true);
  private final AtomicLong matchSequence = new AtomicLong();
  private final AtomicBoolean matchRunning = new AtomicBoolean();
  private final AtomicBoolean libraryScanRunning = new AtomicBoolean();
  private final AtomicLong previewSequence = new AtomicLong();
  private final AtomicLong lyricPreviewSequence = new AtomicLong();
  private volatile ExecutorService activeMatchPool;
  private volatile ExecutorService activeScanPool;
  private volatile Thread activeMatchThread;
  private volatile Thread activeScanThread;
  private volatile Future<?> lyricSearchTask;
  private volatile Future<?> lyricLoadTask;
  private volatile boolean disposed;
  private final Map<Integer, Long> rowMatchTokens = new ConcurrentHashMap<>();
  private final ExecutorService previewExecutor =
      Executors.newFixedThreadPool(4, workerThreadFactory("preview-worker"));
  private final SpectrogramService spectrogramService =
      new SpectrogramService();
  private final AtomicLong spectrogramSequence = new AtomicLong();
  private final SpectrogramView spectrogramView = new SpectrogramView();
  private final JLabel spectrogramState =
      new JLabel("请选择歌曲", SwingConstants.CENTER);
  private final List<Future<?>> previewTasks = new ArrayList<>();
  private final AnimatedCardPanel workArea = new AnimatedCardPanel();
  private final JPanel coverCandidates =
      new ViewportWidthPanel(new GridLayout(0, 3, 14, 14));
  private final JPanel currentCoverHost = new JPanel(new BorderLayout());
  private final AspectCoverView focusedCover = new AspectCoverView();
  private final JLabel focusedCoverInfo =
      new JLabel("", SwingConstants.CENTER);
  private JPanel listPanel;
  private JComponent compactSongHeader;
  private JScrollPane tableScroll;
  private JPanel detailPanel;
  private TwoColumnLayout normalBodyLayout;
  private SnapshotBodyPanel normalBodyPanel;
  private final List<TableColumn> expandedColumns = new ArrayList<>();
  private JTableHeader expandedSongHeader;
  private boolean listExpanded;
  private boolean lyricExpanded;
  private boolean coverPreviewOpen;
  private boolean spectrogramOpen;
  private boolean persistentViewsPreloaded;
  private final JLabel coverPreviewTitle =
      new JLabel("", SwingConstants.CENTER);
  private volatile long latestMatchToken;
  private int current = -1;
  private static final boolean dark = detectDarkMode();

  static TagWorkbenchWindow create() {
    WorkbenchLook.acquire();
    try {
      return new TagWorkbenchWindow();
    } catch (RuntimeException | Error error) {
      WorkbenchLook.release();
      throw error;
    }
  }
  static void acquireLook() { WorkbenchLook.acquire(); }
  static void releaseLook() { WorkbenchLook.release(); }

  private TagWorkbenchWindow() {
    super("");
    getRootPane().putClientProperty("JRootPane.useWindowDecorations", true);
    setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    setMinimumSize(new Dimension(1000, 680));
    setSize(1000, 680);
    setLocationRelativeTo(null);
    JPanel root = new JPanel(new BorderLayout(14, 8));
    root.setBorder(new EmptyBorder(0, 18, 12, 18));
    root.add(toolbar(), BorderLayout.NORTH);
    workArea.add(body(), "normal");
    workArea.add(coverPreviewPanel(), "cover");
    workArea.add(lyricsFocusPanel(), "lyrics");
    workArea.add(spectrogramPanel(), "spectrum");
    workOverlayHost = new OverlayButtonHost(workArea);
    workOverlayHost.setOverlayButton(lyricsToggleButton);
    workOverlayHost.setButtonAnchor(lyricScroll);
    status.setForeground(SUB);
    status.setFont(findFont().deriveFont(Font.PLAIN, 13f));
    status.setHorizontalAlignment(SwingConstants.CENTER);
    status.setBorder(new EmptyBorder(0, 8, 0, 0));
    JComponent statusLine = new LogHandle(status, logHint, this::toggleLog);
    statusLine.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    statusLine.setToolTipText("双击展开或收回详细日志");
    logHint.setForeground(UIManager.getColor("Label.disabledForeground"));
    logHint.setFont(logHint.getFont().deriveFont(Font.PLAIN, 10f));
    logArea.setEditable(false);
    logArea.setFont(findFont().deriveFont(Font.PLAIN, 12f));
    logArea.setOpaque(false);
    logArea.setBorder(new EmptyBorder(8, 10, 8, 10));
    logScroll = scroll(logArea);
    logScroll.setPreferredSize(new Dimension(0, 0));
    logScroll.setVisible(true);
    JPanel footer = new JPanel(new BorderLayout(0, 3));
    logFooter = footer;
    footer.setOpaque(false);
    footer.add(statusLine, BorderLayout.NORTH);
    footer.add(logScroll, BorderLayout.CENTER);
    root.add(new FooterOverlayHost(workOverlayHost, footer),
             BorderLayout.CENTER);
    setContentPane(root);
    String[] tips = {
        "小提示：在三个区域点右键，就能分别匹配全部标签、基础标签或歌词",
        "勾选跟随播放后，当前播放和切换到的歌曲都会自动进入列表",
        "列表有点挤时，点击右键即可清空列表"};
    int[] tipIndex = {0};
    tipTimer = new Timer(4800, e -> {
      tipIndex[0] = (tipIndex[0] + 1) % tips.length;
      topHint.setText(tips[tipIndex[0]]);
    });
    tipTimer.start();
    appendLogs(List.of(
        "成功 | 工作台 | 版本 1.0.1 | 启动成功",
        "成功 | 运行环境 | CPU 逻辑线程 " +
            Runtime.getRuntime().availableProcessors() +
            " | 扫描与匹配线程按任务数量动态分配，上限 32",
            "成功 | 标签源 | QQ、网易云、Apple、酷狗及聚合源已加载 | 网络状态将在首次匹配时验证"));
  }

  void preloadPersistentViews() {
    if (persistentViewsPreloaded || disposed)
      return;
    persistentViewsPreloaded = true;
    int width = Math.max(1, workArea.getWidth()),
        height = Math.max(1, workArea.getHeight());
    for (Component page : workArea.getComponents()) {
      page.setBounds(0, 0, width, height);
      layoutComponentTree(page);
    }
    if (expandedSongHeader != null)
      expandedSongHeader.getPreferredSize();
    workArea.showInstant("normal");
    workOverlayHost.forceOverlayLayout();
    appendLogs(List.of("成功 | 界面预加载 | 常驻页面框架已准备，显示器 " +
                       displayRefreshRate(this) + " Hz"));
  }

  private JComponent toolbar() {
    JLabel heading = new JLabel("音乐标签工作台");
    heading.setFont(heading.getFont().deriveFont(Font.PLAIN, 28f));
    heading.setVerticalAlignment(SwingConstants.BOTTOM);
    topHint.setForeground(UIManager.getColor("Label.disabledForeground"));
    topHint.setFont(topHint.getFont().deriveFont(11f));
    topHint.setVerticalAlignment(SwingConstants.BOTTOM);
    topHint.setBorder(new EmptyBorder(0, 0, 7, 0));
    JPanel topLine = new JPanel(new BorderLayout(12, 0));
    topLine.setOpaque(false);
    topLine.add(heading, BorderLayout.WEST);
    topLine.add(topHint, BorderLayout.CENTER);
    topLine.setPreferredSize(new Dimension(0, 34));
    JPanel songActions = new JPanel(new GridLayout(1, 3, 6, 0));
    songActions.setOpaque(false);
    songActions.add(button("添加音频", this::addFiles, false));
    songActions.add(button("重载列表", this::reloadList, false));
    songActions.add(button("清空列表", this::clearList, false));
    JPanel sourceActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
    sourceActions.setOpaque(false);
    JLabel sourceLabel = new JLabel("标签源选择:");
    sourceLabel.setBorder(new EmptyBorder(0, 8, 0, 0));
    sourceActions.add(sourceLabel);
    sourceBox.setRenderer(new CenterListRenderer());
    sourceBox.putClientProperty("FlatLaf.style", "arc: 10");
    styleComboPopup(sourceBox);
    sourceBox.setPreferredSize(new Dimension(138, 30));
    sourceActions.add(sourceBox);
    FlowLayout actionFlow = new FlowLayout(FlowLayout.RIGHT, 4, 0);
    actionFlow.setAlignOnBaseline(true);
    JPanel tagActions = new JPanel(actionFlow);
    tagActions.setOpaque(false);
    spectrogramButton =
        button("频谱图", this::toggleSpectrogram, false);
    JButton matchButton = button(
        "匹配选中",
        () -> matchSelected(MatchScope.ALL, selectedSource(), true), true);
    JButton saveCurrentButton = button("保存当前", this::saveCurrent, true);
    JButton saveSelectedButton = button("保存选中", this::saveSelected, true);
    equalizeButtonWidths(spectrogramButton, matchButton, saveCurrentButton,
                         saveSelectedButton);
    tagActions.add(spectrogramButton);
    tagActions.add(matchButton);
    tagActions.add(saveCurrentButton);
    tagActions.add(saveSelectedButton);
    followPlayback.setOpaque(false);
    followPlayback.setHorizontalAlignment(SwingConstants.RIGHT);
    tagActions.add(followPlayback);
    JPanel controls = new JPanel(new RatioLayout(12));
    controls.setOpaque(false);
    controls.add(songActions);
    controls.add(sourceActions);
    controls.add(tagActions);
    controls.setPreferredSize(new Dimension(0, 30));
    JPanel outer = new JPanel(new BorderLayout(0, 3));
    outer.setOpaque(false);
    outer.add(topLine, BorderLayout.NORTH);
    outer.add(controls, BorderLayout.SOUTH);
    return outer;
  }

  private JComponent body() {
    table.setRowHeight(40);
    table.setShowGrid(false);
    table.setIntercellSpacing(new Dimension(0, 0));
    table.setRowSorter(sorter);
    for (int column = 0; column < model.getColumnCount(); column++)
      sorter.setSortable(column, false);
    table.setTableHeader(null);
    table.setDefaultRenderer(String.class, new CenterCellRenderer());
    table.setDefaultRenderer(Boolean.class, new CheckCellRenderer());
    table.addMouseListener(new java.awt.event.MouseAdapter() {
      public void mouseClicked(java.awt.event.MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint()),
            col = table.columnAtPoint(e.getPoint());
        if (row >= 0 && col == 0) {
          int modelRow = table.convertRowIndexToModel(row);
          model.checked.set(modelRow, !model.checked.get(modelRow));
          model.fireTableCellUpdated(modelRow, 0);
          repaintCheckHeaders();
        }
      }
    });
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.getColumnModel().getColumn(0).setMinWidth(30);
    table.getColumnModel().getColumn(0).setPreferredWidth(30);
    table.getColumnModel().getColumn(0).setMaxWidth(30);
    table.getSelectionModel().addListSelectionListener(e -> {
      if (!e.getValueIsAdjusting())
        showRow(table.getSelectedRow());
    });
    TableColumnModel columnsModel = table.getColumnModel();
    for (int i = 4; i < 7; i++)
      expandedColumns.add(columnsModel.getColumn(i));
    for (TableColumn column : expandedColumns)
      columnsModel.removeColumn(column);
    tableScroll = scroll(table);
    tableScroll.setBorder(new EmptyBorder(0, 0, 0, 0));
    tableScroll.getViewport().addChangeListener(e -> hideSongTableToolTip());
    listPanel = new JPanel(new BorderLayout(0, 5));
    listPanel.setBorder(titled("歌曲列表"));
    JTableHeader compactHeader =
        new UnifiedTableHeader(columnsModel, true, model);
    compactHeader.setTable(table);
    compactHeader.setReorderingAllowed(false);
    compactHeader.setResizingAllowed(false);
    compactSongHeader = compactHeader;
    expandedSongHeader =
        new UnifiedTableHeader(columnsModel, true, model);
    expandedSongHeader.setTable(table);
    expandedSongHeader.setReorderingAllowed(false);
    expandedSongHeader.setResizingAllowed(true);
    listPanel.add(compactSongHeader, BorderLayout.NORTH);
    listPanel.add(tableScroll, BorderLayout.CENTER);
    JPanel searchBar = new JPanel(new BorderLayout(5, 0));
    searchBar.setOpaque(false);
    listSearch.putClientProperty("JTextField.placeholderText",
                                 "支持搜索除歌词外所有标签");
    listSearch.setHorizontalAlignment(SwingConstants.CENTER);
    listSearch.addActionListener(e -> applyListFilter());
    listSearch.getDocument().addDocumentListener(new DocumentListener() {
      private void refresh() {
        SwingUtilities.invokeLater(() -> applyListFilter());
      }
      public void insertUpdate(DocumentEvent e) { refresh(); }
      public void removeUpdate(DocumentEvent e) { refresh(); }
      public void changedUpdate(DocumentEvent e) { refresh(); }
    });
    searchBar.add(listSearch, BorderLayout.CENTER);
    searchBar.add(listExpandButton, BorderLayout.EAST);
    listPanel.add(searchBar, BorderLayout.SOUTH);
    installListMenu(tableScroll);
    cover.setPreferredSize(new Dimension(260, 260));
    cover.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    cover.setToolTipText("双击查看多个来源的封面匹配结果");
    cover.addMouseListener(new java.awt.event.MouseAdapter() {
      public void mouseClicked(java.awt.event.MouseEvent e) {
        if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2)
          runPageAction("封面预览",
                        TagWorkbenchWindow.this::openCoverPreview);
      }
    });
    JPanel metadata = new JPanel(new BorderLayout(0, 7));
    metadata.setOpaque(false);
    metadata.setBorder(new EmptyBorder(10, 14, 12, 7));
    JPanel coverWrap = new JPanel(new BorderLayout(0, 0));
    coverWrap.setOpaque(false);
    JPanel coverCenter = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
    coverCenter.setOpaque(false);
    coverCenter.add(cover);
    coverWrap.add(coverCenter, BorderLayout.CENTER);
    coverInfo.setForeground(UIManager.getColor("Label.disabledForeground"));
    coverInfo.setFont(coverInfo.getFont().deriveFont(10f));
    coverInfo.setPreferredSize(new Dimension(0, 12));
    coverInfo.setBorder(new EmptyBorder(2, 0, 0, 0));
    coverWrap.add(coverInfo, BorderLayout.SOUTH);
    metadata.add(coverWrap, BorderLayout.NORTH);
    installSourceMenu(cover, MatchScope.COVER);
    JScrollPane fieldScroll = scroll(fields());
    metadataScroll = fieldScroll;
    comment.addMouseWheelListener(e -> {
      SmoothScrollSupport.scroll(metadataScroll,
                                 e.getPreciseWheelRotation());
      e.consume();
    });
    fieldScroll.setHorizontalScrollBarPolicy(
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    fieldScroll.getVerticalScrollBar().setUnitIncrement(36);
    metadata.add(fieldScroll, BorderLayout.CENTER);
    JPanel lyricPanel = new JPanel(new BorderLayout());
    lyricPanel.setOpaque(false);
    lyricPanel.setBorder(new EmptyBorder(10, 7, 12, 14));
    lyricScroll = scroll(lyrics);
    lyricPanel.add(lyricScroll, BorderLayout.CENTER);
    installSourceMenu(lyricScroll, MatchScope.LYRICS);
    JPanel detail = new JPanel(new GridLayout(1, 2, 12, 0));
    detailPanel = detail;
    detail.setOpaque(false);
    detail.setBorder(titled("标签信息"));
    detail.add(metadata);
    detail.add(lyricPanel);
    listPanel.setMinimumSize(new Dimension(0, 0));
    metadata.setMinimumSize(new Dimension(0, 0));
    lyricPanel.setMinimumSize(new Dimension(0, 0));
    detail.setMinimumSize(new Dimension(0, 0));
    normalBodyLayout = new TwoColumnLayout(12, .30);
    normalBodyPanel = new SnapshotBodyPanel(normalBodyLayout);
    normalBodyPanel.setOpaque(false);
    normalBodyPanel.add(listPanel);
    normalBodyPanel.add(detail);
    return normalBodyPanel;
  }

  private JPanel fields() {
    JPanel p = new ViewportWidthPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(5, 5, 5, 5);
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1;
    int r = 0;
    r = row(p, c, r, "标题", title);
    r = row(p, c, r, "艺术家", artist);
    r = row(p, c, r, "专辑", album);
    r = row(p, c, r, "年份", year);
    JPanel nums = new JPanel(new GridBagLayout());
    nums.setOpaque(false);
    GridBagConstraints nc = new GridBagConstraints();
    nc.gridy = 0;
    nc.fill = GridBagConstraints.HORIZONTAL;
    nc.weightx = 1;
    nc.gridx = 0;
    nums.add(track, nc);
    nc.weightx = 0;
    nc.gridx = 1;
    nc.insets = new Insets(0, 8, 0, 6);
    nums.add(new JLabel("碟号"), nc);
    nc.weightx = 1;
    nc.gridx = 2;
    nc.insets = new Insets(0, 0, 0, 0);
    nums.add(disc, nc);
    c.gridwidth = 1;
    c.gridx = 0;
    c.gridy = r;
    c.weightx = 0;
    p.add(new JLabel("音轨号"), c);
    c.gridx = 1;
    c.weightx = 1;
    p.add(nums, c);
    r++;
    r = row(p, c, r, "风格", genre);
    r = row(p, c, r, "专辑艺术家", albumArtist);
    r = row(p, c, r, "作曲家", composer);
    r = row(p, c, r, "作词家", lyricist);
    r = row(p, c, r, "注释", roundedTextArea(comment));
    c.gridx = 0;
    c.gridy = r;
    c.gridwidth = 2;
    c.weightx = 1;
    c.weighty = 1;
    c.fill = GridBagConstraints.VERTICAL;
    c.anchor = GridBagConstraints.NORTHWEST;
    p.add(Box.createVerticalGlue(), c);
    return p;
  }

  @Override
  public void dispose() {
    if (disposed)
      return;
    disposed = true;
    if (tipTimer != null)
      tipTimer.stop();
    if (logAnimation != null)
      logAnimation.stop();
    if (normalBodyPanel != null)
      normalBodyPanel.disposeAnimation();
    previewSequence.incrementAndGet();
    lyricPreviewSequence.incrementAndGet();
    spectrogramSequence.incrementAndGet();
    latestMatchToken = matchSequence.incrementAndGet();
    Thread matchThread = activeMatchThread;
    if (matchThread != null)
      matchThread.interrupt();
    ExecutorService matchPool = activeMatchPool;
    if (matchPool != null)
      matchPool.shutdownNow();
    Thread scanThread = activeScanThread;
    if (scanThread != null)
      scanThread.interrupt();
    ExecutorService scanPool = activeScanPool;
    if (scanPool != null)
      scanPool.shutdownNow();
    releaseLyricPreviewResults();
    cancelPreviewTasks();
    previewExecutor.shutdownNow();
    spectrogramService.close();
    spectrogramView.dispose();
    releaseCoverPreviewResults();
    currentCoverHost.removeAll();
    workArea.disposeAnimation();
    SmoothScrollSupport.disposeTree(getContentPane());
    MusicSources.clearCoverCache();
    ToolTipManager.sharedInstance().unregisterComponent(table);
    ToolTipManager.sharedInstance().unregisterComponent(lyricCandidateTable);
    current = -1;
    model.clear();
    rowMatchTokens.clear();
    rawFocusedLyrics = "";
    lyrics.setText("");
    focusedLyrics.setText("");
    comment.setText("");
    cover.setCover(null);
    try {
      logArea.getDocument().remove(0, logArea.getDocument().getLength());
    } catch (BadLocationException ignored) {
    }
    getContentPane().removeAll();
    try {
      super.dispose();
    } finally {
      TagWorkbenchLauncher.onWindowDisposed(this);
      WorkbenchLook.release();
    }
  }

  private void toggleListExpansion() {
    runPageAction("歌曲列表", this::toggleListExpansionImpl);
  }
  private void toggleListExpansionImpl() {
    if (normalBodyLayout == null)
      return;
    boolean expanding = !listExpanded;
    listExpanded = expanding;
    lyricsToggleButton.setVisible(!expanding);
    normalBodyPanel.animate(expanding, () -> {
      if (expanding)
        prepareExpandedList();
      else
        finishCompactList();
      normalBodyLayout.setRatio(expanding ? 1d : .30d);
      listExpandButton.setText(expanding ? "收回" : "展开");
      normalBodyPanel.doLayout();
    });
  }
  private void toggleLog() {
    runPageAction("日志面板", this::toggleLogImpl);
  }
  private void toggleLogImpl() {
    if (logAnimation != null)
      logAnimation.stop();
    logExpanded = !logExpanded;
    boolean show = logExpanded;
    int from = logScroll.getPreferredSize().height;
    int to = show ? 193 : 0;
    logScroll.setVisible(true);
    logHint.setText(show ? "down" : "up");
    long began = System.nanoTime(), duration = 220_000_000L;
    logAnimation = new Timer(frameDelay(this), event -> {
      float progress = (float)Math.min(
          1d, (System.nanoTime() - began) / (double)duration);
      int height = Math.round(from + (to - from) * motionCurve(progress));
      logScroll.setPreferredSize(new Dimension(0, Math.max(0, height)));
      forceWorkbenchLayout();
      if (progress >= 1f) {
        logAnimation.stop();
        logAnimation = null;
        logScroll.setPreferredSize(new Dimension(0, to));
        forceWorkbenchLayout();
        if (show)
          logArea.setCaretPosition(logArea.getDocument().getLength());
      }
    });
    logAnimation.start();
  }
  private void forceWorkbenchLayout() {
    if (logFooter != null)
      logFooter.invalidate();
    Container content = getContentPane();
    content.invalidate();
    layoutComponentTree(content);
    content.repaint();
  }
  private static void layoutComponentTree(Component component) {
    if (component instanceof Container container) {
      container.doLayout();
      for (Component child : container.getComponents())
        layoutComponentTree(child);
    }
  }
  private void appendLogs(List<String> lines) {
    if (disposed || lines.isEmpty())
      return;
    String time = java.time.LocalTime.now().withNano(0).toString();
    StyledDocument doc = logArea.getStyledDocument();
    Style normal = logArea.addStyle("normal", null);
    StyleConstants.setForeground(normal,
                                 UIManager.getColor("TextPane.foreground"));
    Style warning = logArea.addStyle("warning", normal);
    StyleConstants.setForeground(warning, dark ? new Color(235, 180, 70)
                                               : new Color(180, 115, 0));
    Style error = logArea.addStyle("error", normal);
    StyleConstants.setForeground(error, dark ? new Color(255, 105, 105)
                                             : new Color(205, 45, 45));
    for (String line : lines) {
        boolean failed = isRedLogLine(line),
            warn = line.startsWith("警告") ||
                   line.startsWith("部分填充") ||
                   line.startsWith("近似匹配") || line.startsWith("跳过") ||
                   line.startsWith("降级") ||
                   line.startsWith("相似匹配") ||
                   line.startsWith("相似标题");
      int split = failed || warn ? line.lastIndexOf(" | ") : -1;
      if (split < 0 && line.startsWith("部分填充"))
        split = line.indexOf("未提供：") - 3;
      String prefix = "[" + time + "] " +
                      (split >= 0 ? line.substring(0, split + 3) : line),
             suffix = split >= 0 ? line.substring(split + 3) : "";
      try {
        doc.insertString(doc.getLength(), prefix, normal);
        doc.insertString(doc.getLength(), suffix,
                         failed ? error
                         : warn ? warning
                                : normal);
        doc.insertString(doc.getLength(), System.lineSeparator(), normal);
      } catch (BadLocationException ignored) {
      }
    }
    logArea.setCaretPosition(doc.getLength());
  }
  private static boolean isRedLogLine(String line) {
    return line != null &&
           (line.startsWith("失败") || line.startsWith("异常"));
  }
  private static String elapsedText(long startedNanos) {
    long millis = Math.max(
        0, (System.nanoTime() - startedNanos) / 1_000_000L);
    if (millis < 1000)
      return millis + " 毫秒";
    if (millis < 60_000)
      return String.format(java.util.Locale.ROOT, "%.2f 秒",
                           millis / 1000d);
    return String.format(java.util.Locale.ROOT, "%d 分 %02d 秒",
                         millis / 60_000, millis / 1000 % 60);
  }
  private static String errorDetail(Throwable error) {
    if (error == null)
      return "无详细信息";
    String message = error.getMessage();
    return error.getClass().getSimpleName() + "：" +
        (message == null || message.isBlank() ? "无详细信息" : message);
  }
  private void statusDefault(String text) {
    status.setForeground(SUB);
    status.setText(text);
  }
  private void statusSuccess(String text) {
    status.setForeground(new Color(46, 160, 86));
    status.setText(text);
  }
  private void statusWarning(String text) {
    status.setForeground(dark ? new Color(235, 180, 70)
                              : new Color(180, 115, 0));
    status.setText(text);
  }
  private void statusError(String text) {
    status.setForeground(dark ? new Color(255, 105, 105)
                              : new Color(205, 45, 45));
    status.setText(text);
  }
  private void runPageAction(String page, Runnable action) {
    if (disposed)
      return;
    try {
      action.run();
    } catch (RuntimeException error) {
      previewSequence.incrementAndGet();
      lyricPreviewSequence.incrementAndGet();
      spectrogramSequence.incrementAndGet();
      cancelPreviewTasks();
      releaseLyricPreviewResults();
      spectrogramService.clearResults();
      spectrogramView.reset();
      coverPreviewOpen = false;
      lyricExpanded = false;
      spectrogramOpen = false;
      releaseCoverPreviewResults();
      workArea.showInstant("normal");
      lyricsToggleButton.setText("展开");
      lyricsToggleButton.setVisible(!listExpanded);
      workOverlayHost.setButtonAnchor(lyricScroll);
      statusError(page + "切换失败，已安全返回工作台");
      appendLogs(List.of("异常 | 界面切换 | " + page + " | " +
                         errorDetail(error)));
    }
  }
  private void repaintCheckHeaders() {
    if (compactSongHeader != null)
      compactSongHeader.repaint();
    if (tableScroll != null && tableScroll.getColumnHeader() != null)
      tableScroll.getColumnHeader().repaint();
  }
  private void prepareExpandedList() {
    TableColumnModel model = table.getColumnModel();
    for (TableColumn column : expandedColumns) {
      boolean present = false;
      for (int i = 0; i < model.getColumnCount(); i++)
        if (model.getColumn(i) == column) {
          present = true;
          break;
        }
      if (!present)
        model.addColumn(column);
    }
    int[] widths = {42, 300, 210, 250, 85, 85, 100};
    for (int i = 0; i < model.getColumnCount(); i++) {
      TableColumn column = model.getColumn(i);
      int index = column.getModelIndex();
      if (index >= 0 && index < widths.length)
        column.setPreferredWidth(widths[index]);
    }
    tableScroll.setColumnHeaderView(expandedSongHeader);
    compactSongHeader.setVisible(false);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    listPanel.revalidate();
  }
  private void finishCompactList() {
    TableColumnModel model = table.getColumnModel();
    for (int i = model.getColumnCount() - 1; i >= 0; i--)
      if (model.getColumn(i).getModelIndex() >= 4)
        model.removeColumn(model.getColumn(i));
    tableScroll.setColumnHeaderView(null);
    compactSongHeader.setVisible(true);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
    listPanel.revalidate();
  }
  static int displayRefreshRate(Component component) {
    try {
      GraphicsConfiguration configuration =
          component == null ? null : component.getGraphicsConfiguration();
      GraphicsDevice device =
          configuration == null
              ? GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
              : configuration.getDevice();
      int hz = device.getDisplayMode().getRefreshRate();
      return hz <= 0 ? 60 : Math.max(24, Math.min(360, hz));
    } catch (Exception ignored) {
      return 60;
    }
  }
  static int frameDelay(Component component) {
    return Math.max(2, Math.round(
        1000f / displayRefreshRate(component)));
  }
  private static BufferedImage animationBuffer(Component component,
                                               int width, int height) {
    int safeWidth = Math.max(1, width), safeHeight = Math.max(1, height);
    GraphicsConfiguration configuration =
        component == null ? null : component.getGraphicsConfiguration();
    if (configuration != null)
      try {
        return configuration.createCompatibleImage(
            safeWidth, safeHeight, Transparency.TRANSLUCENT);
      } catch (RuntimeException ignored) {
      }
    return new BufferedImage(safeWidth, safeHeight,
                             BufferedImage.TYPE_INT_ARGB);
  }
  private static float motionCurve(float progress) {
    return progress < .5f
        ? 2f * progress * progress
        : 1f - (float)Math.pow(-2f * progress + 2f, 2) / 2f;
  }

  private JComponent spectrogramPanel() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setOpaque(false);
    panel.setBorder(titled("歌曲频谱图"));
    JPanel canvasHost = new JPanel(new BorderLayout());
    canvasHost.setOpaque(false);
    canvasHost.setBorder(new EmptyBorder(4, 10, 2, 10));
    canvasHost.add(spectrogramView);
    panel.add(canvasHost, BorderLayout.CENTER);
    return panel;
  }

  private void toggleSpectrogram() {
    runPageAction("频谱图", () -> {
      if (spectrogramOpen)
        closeSpectrogram();
      else
        openSpectrogram();
    });
  }

  private void openSpectrogram() {
    sync();
    if (current < 0 || current >= model.items.size()) {
      statusDefault("请先在歌曲列表中选择一首歌曲");
      return;
    }
    AudioTagData selected = model.items.get(current);
    boolean leavingLyrics = lyricExpanded;
    boolean leavingCover = coverPreviewOpen;
    if (leavingLyrics) {
      lyricExpanded = false;
      lyricsToggleButton.setText("展开");
      lyrics.setText(focusedLyrics.getText());
      lyrics.setCaretPosition(0);
    }
    if (leavingCover) {
      coverPreviewOpen = false;
      previewSequence.incrementAndGet();
      cancelPreviewTasks();
    }
    previewSequence.incrementAndGet();
    cancelPreviewTasks();
    spectrogramOpen = true;
    lyricsToggleButton.setVisible(false);
    long token = spectrogramSequence.incrementAndGet();
    String displayTitle = selected.title().isBlank()
        ? selected.file().getName().replaceFirst("\\.[^.]+$", "")
        : selected.title();
    long startedNanos = System.nanoTime();
    spectrogramState.setText("正在调用 SPW 音频引擎生成声道频谱");
    statusDefault("正在调用 SPW 音频引擎生成声道频谱");
    appendLogs(List.of("提示 | 频谱图 | " + displayTitle +
                       " | 开始解码并计算左右声道"));
    spectrogramView.reset();
    workArea.showFadeAnimated("spectrum");
    if (leavingLyrics)
      releaseLyricPreviewResults();
    if (leavingCover)
      releaseCoverPreviewResults();
    forceWorkbenchLayout();
    SwingUtilities.invokeLater(() -> {
      if (!spectrogramOpen || token != spectrogramSequence.get())
        return;
      int width = Math.max(520, spectrogramView.getWidth() - 78);
      int height = Math.max(220, spectrogramView.getHeight() - 112);
      spectrogramService.render(
          selected.file(), width, height,
          new SpectrogramService.Listener() {
            @Override
            public void onProgress(SpectrogramService.Rendered rendered,
                                   int percent) {
              SwingUtilities.invokeLater(() -> {
                if (!spectrogramOpen ||
                    token != spectrogramSequence.get())
                  return;
                spectrogramView.setRendered(rendered, percent);
                spectrogramState.setText(
                    "正在生成频谱 " + percent + "% · " +
                    channelText(rendered.channels()));
              });
            }
            @Override
            public void onComplete(SpectrogramService.Rendered rendered) {
              SwingUtilities.invokeLater(() -> {
                if (!spectrogramOpen ||
                    token != spectrogramSequence.get())
                  return;
                spectrogramView.setRendered(rendered, 100);
                String details =
                    channelText(rendered.channels()) + " · " +
                    sampleRateText(rendered.sampleRate()) +
                    " · FFT 4096 · " +
                    durationText(rendered.durationSeconds());
                spectrogramState.setText(details);
                statusSuccess("频谱图生成完成");
                appendLogs(List.of("成功 | " + displayTitle +
                                   " | 频谱图 | " + details + "，用时 " +
                                   elapsedText(startedNanos)));
              });
            }
            @Override
            public void onFailure(String message, Throwable error) {
              SwingUtilities.invokeLater(() -> {
                if (!spectrogramOpen ||
                    token != spectrogramSequence.get())
                  return;
                spectrogramView.setFailure(message);
                spectrogramState.setText(message);
                statusError("频谱图生成失败，双击底栏可查看详细信息");
                String detail =
                    error == null ? message
                    : error.getClass().getSimpleName() + "：" +
                          (error.getMessage() == null
                               ? message
                               : error.getMessage());
                appendLogs(List.of("失败 | " + displayTitle +
                                   " | 频谱图 | " + message + " | " +
                                   detail + "，用时 " +
                                   elapsedText(startedNanos)));
              });
            }
          });
    });
  }

  private void closeSpectrogram() {
    if (!spectrogramOpen)
      return;
    spectrogramOpen = false;
    spectrogramSequence.incrementAndGet();
    workArea.showFadeAnimated("normal");
    spectrogramView.reset();
    spectrogramService.clearResults();
    lyricsToggleButton.setVisible(!listExpanded);
    workOverlayHost.setButtonAnchor(lyricScroll);
    forceWorkbenchLayout();
  }

  private static String channelText(int channels) {
    return channels >= 2 ? "双声道（左 / 右）" : "单声道";
  }

  private static String sampleRateText(int sampleRate) {
    if (sampleRate <= 0)
      return "采样率未知";
    if (sampleRate % 1000 == 0)
      return sampleRate / 1000 + " kHz";
    return String.format(java.util.Locale.ROOT, "%.1f kHz",
                         sampleRate / 1000d);
  }

  private static String durationText(double seconds) {
    long rounded = Math.max(0, Math.round(seconds));
    return String.format(java.util.Locale.ROOT, "%d:%02d",
                         rounded / 60, rounded % 60);
  }

  private JComponent coverPreviewPanel() {
    JPanel panel = new JPanel(new BorderLayout(0, 8));
    panel.setBorder(titled("封面预览"));
    currentCoverHost.setOpaque(false);
    JPanel currentBox = new JPanel(new BorderLayout(0, 7));
    currentBox.setOpaque(false);
    currentBox.setBorder(new EmptyBorder(2, 4, 2, 4));
    currentBox.add(new JLabel("当前封面", SwingConstants.CENTER),
                   BorderLayout.NORTH);
    focusedCover.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    focusedCover.setToolTipText("双击收回预览");
    focusedCover.addMouseListener(new java.awt.event.MouseAdapter() {
      public void mouseClicked(java.awt.event.MouseEvent event) {
        if (SwingUtilities.isLeftMouseButton(event) &&
            event.getClickCount() == 2)
          runPageAction("封面预览",
                        TagWorkbenchWindow.this::closeCoverPreview);
      }
    });
    currentBox.add(focusedCover, BorderLayout.CENTER);
    JPanel currentBottom = new JPanel(new GridLayout(0, 1, 0, 5));
    currentBottom.setOpaque(false);
    currentBottom.add(focusedCoverInfo);
    JPanel coverButtons = new JPanel(new GridLayout(1, 2, 8, 0));
    coverButtons.setOpaque(false);
    coverButtons.add(button("导入封面", () -> {
      importCover();
      if (coverPreviewOpen && current >= 0 &&
          current < model.items.size())
        showCurrentCover(model.items.get(current));
    }, false));
    coverButtons.add(button("导出封面", this::exportCover, false));
    currentBottom.add(coverButtons);
    currentBox.add(currentBottom, BorderLayout.SOUTH);
    currentCoverHost.add(currentBox);
    JPanel matches = new JPanel(new BorderLayout(0, 7));
    matches.setOpaque(false);
    matches.add(
        new JLabel("匹配结果（每个来源至少显示 3 条）", SwingConstants.CENTER),
        BorderLayout.NORTH);
    coverCandidates.setOpaque(false);
    JScrollPane scroll = scroll(coverCandidates);
    scroll.setHorizontalScrollBarPolicy(
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    matches.add(scroll, BorderLayout.CENTER);
    JPanel content = new JPanel(new TwoColumnLayout(18, .36));
    content.setOpaque(false);
    content.add(currentCoverHost);
    content.add(matches);
    panel.add(content, BorderLayout.CENTER);
    JButton back = button(
        "收回视图",
        () -> runPageAction("封面预览", this::closeCoverPreview), false);
    JPanel footer = new JPanel(new BorderLayout(8, 0));
    footer.setOpaque(false);
    footer.add(Box.createHorizontalStrut(back.getPreferredSize().width),
               BorderLayout.WEST);
    coverPreviewTitle.setFont(coverPreviewTitle.getFont().deriveFont(15f));
    footer.add(coverPreviewTitle, BorderLayout.CENTER);
    footer.add(back, BorderLayout.EAST);
    panel.add(footer, BorderLayout.SOUTH);
    return panel;
  }
  private JComponent lyricsFocusPanel() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setOpaque(false);
    panel.setBorder(titled("歌词预览"));
    JPanel inner = new JPanel(new TwoColumnLayout(14, .42));
    inner.setOpaque(false);
    inner.setBorder(new EmptyBorder(10, 7, 12, 14));
    focusedLyricScroll = scroll(focusedLyrics);
    lyricCandidateTable.setRowHeight(36);
    lyricCandidateTable.setShowGrid(false);
    lyricCandidateTable.setIntercellSpacing(new Dimension(0, 0));
    lyricCandidateTable.setFillsViewportHeight(true);
    lyricCandidateTable.setDefaultRenderer(String.class,
                                            new CompactWrapRenderer());
    lyricCandidateTable.setFont(findFont().deriveFont(Font.PLAIN, 11.5f));
    lyricCandidateTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    lyricCandidateTable.addMouseListener(new java.awt.event.MouseAdapter() {
      public void mouseClicked(java.awt.event.MouseEvent event) {
        if (SwingUtilities.isLeftMouseButton(event) &&
            event.getClickCount() == 2)
          applySelectedLyricCandidate();
      }
    });
    JTableHeader candidateHeader =
        new UnifiedTableHeader(lyricCandidateTable.getColumnModel(), true, null);
    candidateHeader.setTable(lyricCandidateTable);
    candidateHeader.setReorderingAllowed(false);
    candidateHeader.setResizingAllowed(false);
    lyricCandidateTable.setTableHeader(null);
    lyricCandidateTable.getColumnModel().getColumn(0).setPreferredWidth(135);
    lyricCandidateTable.getColumnModel().getColumn(1).setPreferredWidth(90);
    lyricCandidateTable.getColumnModel().getColumn(2).setPreferredWidth(92);
    lyricCandidateTable.getColumnModel().getColumn(3).setPreferredWidth(55);
    lyricCandidateTable.getColumnModel().getColumn(4).setMinWidth(76);
    lyricCandidateTable.getColumnModel().getColumn(4).setPreferredWidth(76);
    lyricCandidateTable.getColumnModel().getColumn(4).setMaxWidth(84);
    JScrollPane candidates = scroll(lyricCandidateTable);
    candidates.setBorder(new EmptyBorder(0, 0, 0, 0));
    candidates.getViewport().addChangeListener(
        e -> hideTableToolTip(lyricCandidateTable));
    candidates.setHorizontalScrollBarPolicy(
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    JPanel candidatePanel = new JPanel(new BorderLayout(0, 0));
    candidatePanel.setOpaque(false);
    candidatePanel.setBorder(
        new RoundedBorder(new Color(218, 221, 227), 12));
    candidatePanel.add(candidateHeader, BorderLayout.NORTH);
    candidatePanel.add(candidates, BorderLayout.CENTER);
    JPanel candidateColumn =
        new JPanel(new ClippedSettingsLayout(14, .80,
                                             () -> logExpanded));
    candidateColumn.setOpaque(false);
    candidateColumn.add(candidatePanel);
    candidateColumn.add(lyricSettingsPanel());
    inner.add(candidateColumn);
    inner.add(focusedLyricScroll);
    panel.add(inner, BorderLayout.CENTER);
    return panel;
  }
  private JComponent lyricSettingsPanel() {
    JPanel panel = new JPanel(new GridLayout(2, 2, 6, 2));
    panel.setOpaque(false);
    panel.setBorder(new CompoundBorder(
        new RoundedBorder(new Color(218, 221, 227), 12),
        new EmptyBorder(4, 6, 4, 6)));
    lyricFormatBox.putClientProperty("FlatLaf.style", "arc: 10");
    styleComboPopup(lyricFormatBox);
    Dimension formatSize = new Dimension(112, 26);
    lyricFormatBox.setPreferredSize(formatSize);
    lyricFormatBox.setMinimumSize(formatSize);
    lyricFormatBox.setMaximumSize(formatSize);
    lyricFormatBox.setRenderer(new EllipsisComboRenderer(82));
    lyricFormatBox.setToolTipText(
        String.valueOf(lyricFormatBox.getSelectedItem()));
    lyricFormatBox.addActionListener(event ->
        lyricFormatBox.setToolTipText(
            String.valueOf(lyricFormatBox.getSelectedItem())));
    JPanel formatRow = labeledControl("歌词格式", lyricFormatBox);
    JPanel languages = compactFlow();
    languages.add(lyricOriginal);
    languages.add(lyricTranslation);
    languages.add(lyricRomanization);
    lyricOffset.setPreferredSize(new Dimension(112, 26));
    lyricOffset.putClientProperty("FlatLaf.style", "arc: 10");
    if (lyricOffset.getEditor() instanceof JSpinner.DefaultEditor editor) {
      editor.getTextField().setHorizontalAlignment(SwingConstants.CENTER);
      editor.getTextField().putClientProperty("FlatLaf.style", "arc: 10");
    }
    JPanel offsetRow = labeledControl("偏移量", lyricOffset);
    JPanel chinese = compactFlow();
    JButton toSimple =
        button("转简", () -> convertFocusedChinese(false), false);
    JButton toTraditional =
        button("转繁", () -> convertFocusedChinese(true), false);
    toSimple.setPreferredSize(
        new Dimension(toSimple.getPreferredSize().width, 26));
    toTraditional.setPreferredSize(
        new Dimension(toTraditional.getPreferredSize().width, 26));
    chinese.add(toSimple);
    chinese.add(toTraditional);
    panel.add(formatRow);
    panel.add(chinese);
    panel.add(offsetRow);
    panel.add(languages);
    lyricFormatBox.addActionListener(event -> applyLyricSettings());
    lyricOriginal.addActionListener(event -> applyLyricSettings());
    lyricTranslation.addActionListener(event -> applyLyricSettings());
    lyricRomanization.addActionListener(event -> applyLyricSettings());
    lyricOffset.addChangeListener(event -> applyLyricSettings());
    return panel;
  }
  private static JPanel compactFlow() {
    JPanel panel = new JPanel(new GridBagLayout()) {
      private int column;
      @Override
      protected void addImpl(Component component, Object ignored, int index) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column++;
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.CENTER;
        constraints.insets =
            new Insets(0, constraints.gridx == 0 ? 0 : 5, 0, 0);
        super.addImpl(component, constraints, index);
      }
    };
    panel.setOpaque(false);
    return panel;
  }
  private static JPanel labeledControl(String text, JComponent control) {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setOpaque(false);
    JLabel label = new JLabel(text, SwingConstants.RIGHT);
    label.setPreferredSize(new Dimension(52, 26));
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = 0;
    constraints.gridy = 0;
    constraints.anchor = GridBagConstraints.CENTER;
    constraints.insets = new Insets(0, 0, 0, 5);
    panel.add(label, constraints);
    constraints.gridx = 1;
    constraints.insets = new Insets(0, 0, 0, 0);
    panel.add(control, constraints);
    return panel;
  }
  private void resetLyricSettings() {
    updatingLyricSettings = true;
    lyricFormatBox.setSelectedIndex(0);
    lyricOriginal.setSelected(true);
    lyricTranslation.setSelected(true);
    lyricRomanization.setSelected(false);
    lyricOffset.setValue(0);
    updatingLyricSettings = false;
    applyLyricSettings();
  }
  private void convertFocusedChinese(boolean traditional) {
    String source = rawFocusedLyrics.isBlank() ? focusedLyrics.getText()
                                               : rawFocusedLyrics;
    rawFocusedLyrics = ChineseConverter.convert(source, traditional);
    applyLyricSettings();
  }
  private void applyLyricSettings() {
    if (updatingLyricSettings)
      return;
    String source = rawFocusedLyrics.isBlank() ? focusedLyrics.getText()
                                               : rawFocusedLyrics;
    String selected = selectLyricLanguages(source);
    int offset = ((Number)lyricOffset.getValue()).intValue();
    if (offset != 0)
      selected = shiftLyrics(selected, offset);
    selected = switch (lyricFormatBox.getSelectedIndex()) {
      case 1 -> toLineLrc(selected);
      case 2 -> toEnhancedLrc(selected);
      case 3 -> toSrt(selected);
      case 4 -> toAss(selected);
      case 5 -> toPlainLyrics(selected);
      default -> selected;
    };
    int caret = Math.min(focusedLyrics.getCaretPosition(), selected.length());
    focusedLyrics.setText(selected);
    focusedLyrics.setCaretPosition(caret);
  }
  private String selectLyricLanguages(String source) {
    java.util.regex.Pattern time = java.util.regex.Pattern.compile(
        "\\[(\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?)]");
    Map<String, Integer> occurrence = new HashMap<>();
    StringBuilder output = new StringBuilder();
    for (String line : source.split("\\R", -1)) {
      java.util.regex.Matcher matcher = time.matcher(line);
      if (!matcher.find()) {
        if (lyricOriginal.isSelected())
          output.append(line).append('\n');
        continue;
      }
      String timestamp = matcher.group(1);
      int count = occurrence.getOrDefault(timestamp, 0);
      occurrence.put(timestamp, count + 1);
      boolean keep = count == 0 ? lyricOriginal.isSelected()
          : count == 1 ? lyricTranslation.isSelected()
                       : lyricRomanization.isSelected();
      if (keep)
        output.append(line).append('\n');
    }
    return output.toString().stripTrailing();
  }
  private static String shiftLyrics(String source, int offset) {
    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
        "\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    java.util.regex.Matcher matcher = pattern.matcher(source);
    StringBuffer output = new StringBuffer();
    while (matcher.find()) {
      long fraction = matcher.group(3) == null ? 0
          : Long.parseLong(matcher.group(3)) *
                (matcher.group(3).length() == 1 ? 100
                 : matcher.group(3).length() == 2 ? 10 : 1);
      long value = Long.parseLong(matcher.group(1)) * 60000L +
                   Long.parseLong(matcher.group(2)) * 1000L + fraction +
                   offset;
      value = Math.max(0, value);
      String replacement = String.format(java.util.Locale.ROOT,
          "[%02d:%02d.%03d]", value / 60000, value / 1000 % 60, value % 1000);
      matcher.appendReplacement(output,
                                java.util.regex.Matcher.quoteReplacement(
                                    replacement));
    }
    matcher.appendTail(output);
    return output.toString();
  }
  private static String toLineLrc(String source) {
    java.util.regex.Pattern time = java.util.regex.Pattern.compile(
        "\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?]");
    StringBuilder output = new StringBuilder();
    for (String line : source.split("\\R")) {
      java.util.regex.Matcher matcher = time.matcher(line);
      String first = matcher.find() ? matcher.group() : "";
      String text = time.matcher(line).replaceAll("");
      output.append(first).append(text).append('\n');
    }
    return output.toString().stripTrailing();
  }
  private static String toEnhancedLrc(String source) {
    java.util.regex.Pattern time = java.util.regex.Pattern.compile(
        "\\[(\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?)]");
    StringBuilder output = new StringBuilder();
    for (String line : source.split("\\R")) {
      java.util.regex.Matcher matcher = time.matcher(line);
      if (!matcher.find()) {
        output.append(line).append('\n');
        continue;
      }
      output.append(line, 0, matcher.end());
      int cursor = matcher.end();
      while (matcher.find()) {
        output.append(line, cursor, matcher.start());
        output.append('<').append(matcher.group(1)).append('>');
        cursor = matcher.end();
      }
      output.append(line.substring(cursor)).append('\n');
    }
    return output.toString().stripTrailing();
  }
  private static String toSrt(String source) {
    List<LyricOutputGroup> groups = lyricOutputGroups(source);
    StringBuilder output = new StringBuilder();
    for (int i = 0; i < groups.size(); i++) {
      LyricOutputGroup group = groups.get(i);
      output.append(i + 1).append('\n')
          .append(srtTime(group.start)).append(" --> ")
          .append(srtTime(group.end)).append('\n');
      for (String text : group.texts)
        output.append(text).append('\n');
      output.append('\n');
    }
    return output.toString().stripTrailing();
  }
  private static String toAss(String source) {
    List<LyricOutputGroup> groups = lyricOutputGroups(source);
    StringBuilder output = new StringBuilder(
        "[Script Info]\nScriptType: v4.00+\n"
        + "Timer: 100.0000\n\n[V4+ Styles]\n"
        + "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, "
        + "OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, "
        + "ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, "
        + "Alignment, MarginL, MarginR, MarginV, Encoding\n"
        + "Style: Lyrics,MiSans,20,&H00FFFFFF,&H000000FF,&H00000000,"
        + "&H00000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1\n\n"
        + "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, "
        + "MarginR, MarginV, Effect, Text\n");
    for (LyricOutputGroup group : groups)
      output.append("Dialogue: 0,").append(assTime(group.start)).append(',')
          .append(assTime(group.end))
          .append(",Lyrics,,0,0,0,,")
          .append(String.join("\\N", group.texts).replace("{", "\\{"))
          .append('\n');
    return output.toString().stripTrailing();
  }
  private static List<LyricOutputGroup> lyricOutputGroups(String source) {
    java.util.regex.Pattern time = java.util.regex.Pattern.compile(
        "\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    Map<Long, LyricOutputGroup> grouped = new java.util.LinkedHashMap<>();
    for (String line : source.split("\\R")) {
      java.util.regex.Matcher matcher = time.matcher(line);
      if (!matcher.find())
        continue;
      long start = timestampMillis(matcher);
      long end = start;
      while (matcher.find())
        end = Math.max(end, timestampMillis(matcher));
      String text = time.matcher(line).replaceAll("").trim();
      if (text.isBlank())
        continue;
      LyricOutputGroup group = grouped.computeIfAbsent(
          start, key -> new LyricOutputGroup(key));
      group.end = Math.max(group.end, end);
      group.texts.add(text);
    }
    List<LyricOutputGroup> groups = new ArrayList<>(grouped.values());
    for (int i = 0; i < groups.size(); i++) {
      LyricOutputGroup group = groups.get(i);
      if (group.end <= group.start)
        group.end = i + 1 < groups.size()
            ? Math.max(group.start + 10, groups.get(i + 1).start - 10)
            : group.start + 5000;
    }
    return groups;
  }
  private static long timestampMillis(java.util.regex.Matcher matcher) {
    String fraction = matcher.group(3);
    long millis = fraction == null ? 0 : Long.parseLong(fraction);
    if (fraction != null && fraction.length() == 1)
      millis *= 100;
    else if (fraction != null && fraction.length() == 2)
      millis *= 10;
    return Long.parseLong(matcher.group(1)) * 60000L +
           Long.parseLong(matcher.group(2)) * 1000L + millis;
  }
  private static String srtTime(long millis) {
    return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d",
                         millis / 3600000, millis / 60000 % 60,
                         millis / 1000 % 60, millis % 1000);
  }
  private static String assTime(long millis) {
    return String.format(java.util.Locale.ROOT, "%d:%02d:%02d.%02d",
                         millis / 3600000, millis / 60000 % 60,
                         millis / 1000 % 60, (millis % 1000) / 10);
  }
  private static final class LyricOutputGroup {
    final long start;
    long end;
    final List<String> texts = new ArrayList<>();
    LyricOutputGroup(long start) {
      this.start = start;
      this.end = start;
    }
  }
  private static String toPlainLyrics(String source) {
    return source.replaceAll("\\[[^]]+]", "").replaceAll("(?m)^\\s*$\\R?", "")
        .stripTrailing();
  }
  private void toggleLyricsFocus() {
    runPageAction("歌词预览", () -> {
      if (lyricExpanded)
        closeLyricsFocus();
      else
        openLyricsFocus();
    });
  }
  private void openLyricsFocus() {
    if (lyricExpanded)
      return;
    lyricExpanded = true;
    lyricsToggleButton.setText("收回");
    focusedLyrics.setText(lyrics.getText());
    rawFocusedLyrics = lyrics.getText();
    focusedLyrics.setFont(lyrics.getFont());
    focusedLyrics.setMargin(lyrics.getMargin());
    resetLyricSettings();
    focusedLyrics.setCaretPosition(0);
    workOverlayHost.setButtonAnchor(focusedLyricScroll);
    loadLyricCandidates();
    workArea.showComponentFadeAnimated(
        "lyrics", lyricScroll, focusedLyricScroll);
    forceWorkbenchLayout();
    SwingUtilities.invokeLater(workOverlayHost::forceOverlayLayout);
  }
  private void closeLyricsFocus() {
    if (!lyricExpanded)
      return;
    lyricExpanded = false;
    lyricsToggleButton.setText("展开");
    lyrics.setText(focusedLyrics.getText());
    lyrics.setCaretPosition(0);
    workOverlayHost.setButtonAnchor(lyricScroll);
    workArea.showComponentFadeAnimated(
        "normal", focusedLyricScroll, lyricScroll);
    releaseLyricPreviewResults();
    forceWorkbenchLayout();
    SwingUtilities.invokeLater(workOverlayHost::forceOverlayLayout);
  }
  private void releaseLyricPreviewResults() {
    lyricPreviewSequence.incrementAndGet();
    Future<?> search = lyricSearchTask;
    if (search != null)
      search.cancel(true);
    lyricSearchTask = null;
    Future<?> load = lyricLoadTask;
    if (load != null)
      load.cancel(true);
    lyricLoadTask = null;
    lyricCandidateTable.clearSelection();
    lyricCandidateModel.setRows(List.of());
    rawFocusedLyrics = "";
    focusedLyrics.setText("");
    focusedLyrics.setCaretPosition(0);
  }
  private void loadLyricCandidates() {
    lyricCandidateModel.setRows(List.of());
    if (current < 0 || current >= model.items.size())
      return;
    AudioTagData selected = model.items.get(current);
    String query = selected.title().isBlank()
        ? selected.file().getName().replaceFirst("\\.[^.]+$", "")
        : selected.title();
    long startedNanos = System.nanoTime();
    long token = lyricPreviewSequence.incrementAndGet();
    appendLogs(List.of("提示 | 歌词预览 | " + query +
                       " | 开始查询 QQ音乐、酷狗音乐和网易云音乐"));
    lyricSearchTask = previewExecutor.submit(() -> {
      List<MusicSources.LyricSearchMatch> rows;
      Exception failure = null;
      try {
        rows = MusicSources.lyricSearchMatches(
            query, 5, durationMillis(selected.duration()));
      } catch (Exception ex) {
        failure = ex;
        rows = List.of();
      }
      List<MusicSources.LyricSearchMatch> visible =
          rows.stream().filter(row -> row.relevance() >= 30).toList();
      Exception error = failure;
      int returned = rows.size();
      SwingUtilities.invokeLater(() -> {
        if (disposed || !lyricExpanded ||
            token != lyricPreviewSequence.get())
          return;
        lyricSearchTask = null;
        lyricCandidateModel.setRows(visible);
        if (error != null) {
          appendLogs(List.of("异常 | 歌词预览 | " + query + " | " +
                             errorDetail(error) + "，用时 " +
                             elapsedText(startedNanos)));
          statusError("歌词候选查询异常，双击底栏查看详细信息");
        } else if (returned == 0) {
          appendLogs(List.of("失败 | 歌词预览 | " + query +
                             " | 所有歌词来源均未返回候选，用时 " +
                             elapsedText(startedNanos)));
          statusError("没有找到歌词候选，双击底栏查看详细信息");
        } else if (visible.isEmpty()) {
          appendLogs(List.of("警告 | 歌词预览 | " + query + " | 返回 " +
                             returned +
                             " 条结果，但没有结果达到显示相关度，用时 " +
                             elapsedText(startedNanos)));
          statusWarning("歌词候选相关度过低，未显示结果");
        } else {
          appendLogs(List.of("成功 | 歌词预览 | " + query + " | 返回 " +
                             returned + " 条，显示 " + visible.size() +
                             " 条，用时 " + elapsedText(startedNanos)));
          statusSuccess("歌词候选已载入：" + visible.size() + " 条");
        }
      });
    });
  }
  private static long durationMillis(String value) {
    if (value == null || value.isBlank())
      return 0;
    try {
      String[] parts = value.trim().split(":");
      if (parts.length == 2)
        return (Long.parseLong(parts[0]) * 60 +
                Long.parseLong(parts[1])) * 1000;
      if (parts.length == 3)
        return (Long.parseLong(parts[0]) * 3600 +
                Long.parseLong(parts[1]) * 60 +
                Long.parseLong(parts[2])) * 1000;
    } catch (Exception ignored) {
    }
    return 0;
  }
  private void applySelectedLyricCandidate() {
    int viewRow = lyricCandidateTable.getSelectedRow();
    if (viewRow < 0)
      return;
    MusicSources.LyricSearchMatch match =
        lyricCandidateModel.rowAt(
            lyricCandidateTable.convertRowIndexToModel(viewRow));
    long startedNanos = System.nanoTime();
    long token = lyricPreviewSequence.incrementAndGet();
    Future<?> previous = lyricLoadTask;
    if (previous != null)
      previous.cancel(true);
    statusDefault("正在获取歌词：" + match.title());
    lyricLoadTask = previewExecutor.submit(() -> {
      String value = "";
      Exception failure = null;
      try {
        value = MusicSources.lyricsFor(match);
      } catch (Exception ex) {
        failure = ex;
      }
      String lyricValue = value;
      Exception error = failure;
      SwingUtilities.invokeLater(() -> {
        if (disposed || !lyricExpanded ||
            token != lyricPreviewSequence.get())
          return;
        lyricLoadTask = null;
        if (!lyricValue.isBlank()) {
          focusedLyrics.setText(lyricValue);
          rawFocusedLyrics = lyricValue;
          resetLyricSettings();
          focusedLyrics.setCaretPosition(0);
          statusSuccess("已载入 " + match.source() + " 的歌词，可继续编辑");
          appendLogs(List.of("成功 | 歌词载入 | " + match.title() + " | " +
                             match.source() + " 返回 " +
                             lyricValue.length() + " 个字符，用时 " +
                             elapsedText(startedNanos)));
        } else {
          statusWarning("歌词获取失败，双击底栏可查看详细信息");
          String detail = error == null
              ? "来源未返回可用歌词"
              : error.getClass().getSimpleName() + "：" +
                    (error.getMessage() == null ? "无详细信息"
                                                : error.getMessage());
          appendLogs(List.of("失败 | " + match.title() + " | 歌词 | " +
                             match.source() + " | " + detail + "，用时 " +
                             elapsedText(startedNanos)));
        }
      });
    });
  }
  private void openCoverPreview() {
    sync();
    if (current < 0 || current >= model.items.size()) {
      statusDefault("请先选择一首歌曲");
      appendLogs(List.of("警告 | 封面预览 | 未选择歌曲，无法开始查询"));
      return;
    }
    if (coverPreviewOpen)
      return;
    coverPreviewOpen = true;
    cancelPreviewTasks();
    lyricsToggleButton.setVisible(false);
    int row = current;
    AudioTagData data = model.items.get(row);
    long token = previewSequence.incrementAndGet();
    String localTitle =
        data.title().isBlank()
            ? data.file().getName().replaceFirst("\\.[^.]+$", "")
            : data.title();
    long startedNanos = System.nanoTime();
    AtomicInteger finishedSources = new AtomicInteger();
    AtomicInteger usableImages = new AtomicInteger();
    appendLogs(List.of("提示 | 封面预览 | " + localTitle + " | 开始查询 " +
                       MusicSources.PROVIDERS.size() + " 个来源"));
    coverPreviewTitle.setText("封面匹配结果 · " + localTitle);
    showCurrentCover(data);
    coverCandidates.removeAll();
    for (MusicSource source : MusicSources.PROVIDERS) {
      List<JPanel> loading = new ArrayList<>();
      for (int i = 1; i <= 3; i++) {
        JPanel card = loadingCard(source.name() + " · 正在搜索 " + i + "/3");
        loading.add(card);
        coverCandidates.add(card);
      }
      previewTasks.add(previewExecutor.submit(() -> {
        List<MusicSources.CoverMatch> results = List.of();
        Exception failure = null;
        try {
          results = MusicSources.coverMatches(
              source, (localTitle + " " + data.artist()).trim(), 3);
        } catch (Exception ex) {
          failure = ex;
        }
        List<MusicSources.CoverMatch> returned = results;
        Exception error = failure;
        SwingUtilities.invokeLater(() -> {
          int added = replaceCoverLoading(
              token, loading, returned, source.name(), localTitle,
              data.artist(), durationMillis(data.duration()), row,
              data.file());
          if (added < 0)
            return;
          usableImages.addAndGet(added);
          if (error != null)
            appendLogs(List.of("异常 | 封面预览 | " + source.name() +
                               " | " + errorDetail(error)));
          else if (returned.isEmpty())
            appendLogs(List.of("警告 | 封面预览 | " + source.name() +
                               " 未返回图片"));
          else if (added == 0)
            appendLogs(List.of("警告 | 封面预览 | " + source.name() +
                               " 返回 " + returned.size() +
                               " 张图片，但均未通过歌曲信息确认"));
          else
            appendLogs(List.of("成功 | 封面预览 | " + source.name() +
                               " 返回 " + added + " 张可用图片"));
          if (finishedSources.incrementAndGet() ==
              MusicSources.PROVIDERS.size()) {
            int total = usableImages.get();
            String elapsed = elapsedText(startedNanos);
            if (total == 0) {
              appendLogs(List.of("失败 | 封面预览汇总 | " + localTitle +
                                 " | 所有来源均无可用图片，用时 " + elapsed));
              statusError("封面预览没有可用结果，双击底栏查看详细信息");
            } else {
              appendLogs(List.of("成功 | 封面预览汇总 | " + localTitle +
                                 " | 共取得 " + total + " 张可用图片，用时 " +
                                 elapsed));
              statusSuccess("封面预览已载入：" + total + " 张可用图片");
            }
          }
        });
      }));
    }
    coverCandidates.revalidate();
    animateCoverTransition("cover", true, cover, focusedCover, data.cover());
  }
  private int replaceCoverLoading(long token, List<JPanel> loading,
                                  List<MusicSources.CoverMatch> results,
                                  String source, String localTitle,
                                  String localArtist, long localDuration,
                                  int row, File file) {
    if (token != previewSequence.get() || !isDisplayable())
      return -1;
    for (JPanel card : loading)
      coverCandidates.remove(card);
    int added = 0;
    for (MusicSources.CoverMatch result : results)
      if (result.cover() != null &&
          candidateMatches(localTitle, localArtist, localDuration,
                           result.title(), result.artist(),
                           result.durationMillis())) {
        coverCandidates.add(coverCandidateCard(result.source(), result.title(),
                                               result.artist(), result.cover(),
                                               row, file));
        if (++added >= 3)
          break;
      }
    int accepted = added;
    while (added++ < 3)
      coverCandidates.add(unavailableCard(source + " · 无更多匹配"));
    sortCoverCards();
    coverCandidates.revalidate();
    coverCandidates.repaint();
    return accepted;
  }
  private void sortCoverCards() {
    List<Component> cards =
        new ArrayList<>(List.of(coverCandidates.getComponents()));
    cards.sort(Comparator.comparingInt(
        card
        -> card instanceof JComponent jc && jc.getClientProperty("cover.rank")
                                                    instanceof Integer rank
               ? rank
               : 2));
    coverCandidates.removeAll();
    for (Component card : cards)
      coverCandidates.add(card);
  }
  private void showCurrentCover(AudioTagData data) {
    focusedCover.setImage(data.cover());
    focusedCoverInfo.setText(imageInfo(data.cover()));
    currentCoverHost.repaint();
  }
  private JPanel loadingCard(String text) {
    JPanel card = new JPanel(new BorderLayout());
    card.putClientProperty("cover.rank", 2);
    card.setBorder(
        new CompoundBorder(new RoundedBorder(new Color(220, 223, 228), 16),
                           new EmptyBorder(18, 12, 18, 12)));
    JLabel label = new JLabel(text, SwingConstants.CENTER);
    label.setForeground(SUB);
    card.add(label);
    return card;
  }
  private JPanel unavailableCard(String text) {
    JPanel card = loadingCard(text);
    card.putClientProperty("cover.rank", 1);
    return card;
  }
  private JPanel coverCandidateCard(String source, String song, String singer,
                                    byte[] bytes, int row, File file) {
    JPanel card = new JPanel(new BorderLayout(0, 7));
    card.putClientProperty("cover.rank", 0);
    card.setBorder(
        new CompoundBorder(new RoundedBorder(new Color(220, 223, 228), 16),
                           new EmptyBorder(10, 10, 10, 10)));
    JLabel sourceLabel = new JLabel(source, SwingConstants.CENTER);
    card.add(sourceLabel, BorderLayout.NORTH);
    AspectCoverView preview = new AspectCoverView();
    preview.setPreferredSize(new Dimension(150, 150));
    preview.setImage(bytes);
    card.add(preview, BorderLayout.CENTER);
    JPanel info = new JPanel(new GridLayout(0, 1, 0, 3));
    info.setOpaque(false);
    JLabel songLabel =
               new JLabel(song == null ? "" : song, SwingConstants.CENTER),
           artistLabel =
               new JLabel(singer == null ? "" : singer, SwingConstants.CENTER),
           imageLabel = new JLabel(imageInfo(bytes), SwingConstants.CENTER);
    songLabel.setToolTipText(song);
    artistLabel.setForeground(SUB);
    imageLabel.setForeground(SUB);
    imageLabel.setFont(imageLabel.getFont().deriveFont(10f));
    info.add(songLabel);
    info.add(artistLabel);
    info.add(imageLabel);
    info.add(
        button("使用此封面",
               () -> applyPreviewCover(row, file, bytes, source), true));
    card.add(info, BorderLayout.SOUTH);
    return card;
  }
  private void applyPreviewCover(int row, File file, byte[] bytes,
                                 String source) {
    if (row < 0 || row >= model.items.size() ||
        !model.items.get(row).file().equals(file))
      return;
    AudioTagData d = model.items.get(row);
    model.items.set(
        row, new AudioTagData(d.file(), d.title(), d.artist(), d.album(),
                              d.albumArtist(), d.lyricist(), d.composer(),
                              d.year(), d.track(), d.disc(), d.genre(),
                              d.lyrics(), d.comment(), bytes, d.duration(),
                              d.bitDepth(), d.bitrate()));
    model.fireTableRowsUpdated(row, row);
    if (current == row) {
      cover.setCover(bytes);
      ImageIcon image = new ImageIcon(bytes);
      coverInfo.setText(image.getIconWidth() + " × " + image.getIconHeight() +
                        "px    " + formatBytes(bytes.length));
    }
    statusDefault("已应用封面，保存后写入音频文件");
    appendLogs(List.of("成功 | 封面预览 | " + d.displayName() +
                       " | 已采用 " + source + " 图片，" +
                       imageInfo(bytes) + "，等待保存"));
    runPageAction("封面预览", this::closeCoverPreview);
  }
  private void cancelPreviewTasks() {
    for (Future<?> task : previewTasks)
      task.cancel(true);
    previewTasks.clear();
  }
  private void closeCoverPreview() {
    if (!coverPreviewOpen)
      return;
    coverPreviewOpen = false;
    previewSequence.incrementAndGet();
    cancelPreviewTasks();
    byte[] bytes = current >= 0 && current < model.items.size()
                       ? model.items.get(current).cover()
                       : null;
    animateCoverTransition("normal", false, focusedCover, cover, bytes);
    releaseCoverPreviewResults();
    lyricsToggleButton.setVisible(true);
  }
  private void releaseCoverPreviewResults() {
    releaseImages(coverCandidates);
    coverCandidates.removeAll();
    coverCandidates.revalidate();
    coverCandidates.repaint();
    focusedCover.setImage(null);
    focusedCoverInfo.setText("");
    coverPreviewTitle.setText("");
    MusicSources.clearCoverCache();
  }
  private static void releaseImages(Component component) {
    if (component instanceof AspectCoverView view)
      view.setImage(null);
    if (component instanceof Container container)
      for (Component child : container.getComponents())
        releaseImages(child);
  }
  private void animateCoverTransition(String card, boolean forward,
                                      JComponent source, JComponent target,
                                      byte[] bytes) {
    Border listBorder = listPanel.getBorder(), detailBorder =
                                                   detailPanel.getBorder();
    listPanel.setBorder(invisibleBorder(listPanel, listBorder));
    detailPanel.setBorder(invisibleBorder(detailPanel, detailBorder));
    try {
      workArea.showFocusAnimated(card, forward, source, target, bytes);
    } finally {
      listPanel.setBorder(listBorder);
      detailPanel.setBorder(detailBorder);
    }
  }
  private static Border invisibleBorder(JComponent component, Border border) {
    Insets insets = border == null ? new Insets(0, 0, 0, 0)
                                   : border.getBorderInsets(component);
    return new EmptyBorder(insets);
  }

  private void addFiles() {
    JFileChooser ch = new JFileChooser(FileSystemView.getFileSystemView());
    ch.setMultiSelectionEnabled(true);
    ch.setFileFilter(new FileNameExtensionFilter(
        "音频文件", "mp3", "flac", "m4a", "ogg", "opus", "wav", "wma"));
    File currentFolder = CurrentMediaExtension.currentFolder();
    if (currentFolder != null && currentFolder.isDirectory())
      ch.setCurrentDirectory(currentFolder);
    else {
      File[] roots = FileSystemView.getFileSystemView().getRoots();
      if (roots.length > 0)
        ch.setCurrentDirectory(roots[0]);
    }
    if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
      return;
    File[] selectedFiles = ch.getSelectedFiles();
    if (selectedFiles.length == 0)
      return;
    if (!libraryScanRunning.compareAndSet(false, true)) {
      statusDefault("歌曲列表正在读取文件，请稍候");
      return;
    }
    long startedNanos = System.nanoTime();
    appendLogs(List.of("提示 | 音频导入 | 开始读取 " +
                       selectedFiles.length + " 个文件"));
    setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    Thread importThread = new Thread(() -> {
      List<AudioReadResult> results = new ArrayList<>();
      int workers = workerCount(selectedFiles.length);
      ExecutorService pool = Executors.newFixedThreadPool(
          workers, workerThreadFactory("audio-import-reader"));
      activeScanPool = pool;
      try (pool) {
        List<Future<AudioReadResult>> tasks = new ArrayList<>();
        for (File file : selectedFiles)
          tasks.add(pool.submit(() -> {
            try {
              return new AudioReadResult(file, AudioTagService.read(file),
                                         null);
            } catch (Exception error) {
              return new AudioReadResult(file, null, error);
            }
          }));
        for (Future<AudioReadResult> task : tasks)
          results.add(task.get());
      } catch (Exception error) {
        if (!disposed)
          SwingUtilities.invokeLater(
              () -> appendLogs(List.of("异常 | 音频导入 | " +
                                       errorDetail(error))));
      } finally {
        if (activeScanPool == pool)
          activeScanPool = null;
        if (activeScanThread == Thread.currentThread())
          activeScanThread = null;
      }
      SwingUtilities.invokeLater(() -> {
        libraryScanRunning.set(false);
        if (disposed)
          return;
        int loaded = 0, failed = 0;
        List<String> failures = new ArrayList<>();
        for (AudioReadResult result : results)
          if (result.data() != null) {
            model.add(result.data());
            loaded++;
          } else {
            failed++;
            failures.add("异常 | 音频导入 | " +
                         result.file().getName() + " | " +
                         errorDetail(result.error()));
          }
        if (!failures.isEmpty())
          appendLogs(failures);
        setCursor(Cursor.getDefaultCursor());
        if (model.getRowCount() > 0 && table.getSelectedRow() < 0)
          table.setRowSelectionInterval(0, 0);
        repaintCheckHeaders();
        String summary =
            "音频导入完成：载入 " + loaded + "，失败 " + failed;
        if (failed > 0)
          statusError(summary);
        else
          statusSuccess(summary);
        appendLogs(List.of((failed == 0 ? "成功"
                            : loaded == 0 ? "失败" : "警告") +
                           " | 音频导入汇总 | 请求 " +
                           selectedFiles.length + " 个，载入 " + loaded +
                           " 个，失败 " + failed + " 个，使用 " + workers +
                           " 个线程，用时 " +
                           elapsedText(startedNanos)));
      });
    }, "audio-import");
    importThread.setDaemon(true);
    activeScanThread = importThread;
    importThread.start();
  }
  private record AudioReadResult(File file, AudioTagData data,
                                 Exception error) {}

  void openLibraryFolders(List<File> folders) {
    loadLibraryFolders(folders, false, true);
  }

  private void reloadList() {
    if (matchRunning.get()) {
      statusDefault("匹配任务正在运行，完成后再重载列表");
      return;
    }
    loadLibraryFolders(TagWorkbenchPlugin.configuredMusicFolders(), true,
                       false);
  }

  private void loadLibraryFolders(List<File> folders, boolean replace,
                                  boolean checkedByDefault) {
    if (folders == null || folders.isEmpty()) {
      appendLogs(List.of("提示 | 音乐库 | 未配置自动扫描文件夹"));
      statusDefault("没有可重载的音乐文件夹，请先在模组配置页添加");
      return;
    }
    if (!replace && model.getRowCount() > 0)
      return;
    List<File> valid = folders.stream().filter(File::isDirectory).toList();
    if (valid.isEmpty()) {
      appendLogs(List.of("失败 | 音乐库 | 保存的文件夹路径均不可用"));
      statusWarning("重载失败：保存的音乐文件夹路径不可用");
      return;
    }
    if (!libraryScanRunning.compareAndSet(false, true)) {
      statusDefault("音乐文件夹正在扫描，请稍候");
      return;
    }
    if (replace)
      resetListForReload();
    statusDefault("正在多线程扫描 " + valid.size() + " 个音乐文件夹");
    long startedNanos = System.nanoTime();
    appendLogs(List.of("提示 | 音乐库 | 开始扫描 " + valid.size() +
                       " 个文件夹"));
    Thread scanThread = new Thread(() -> {
      try {
        LinkedHashSet<Path> found = new LinkedHashSet<>();
        for (File folder : valid)
          try (var paths = Files.walk(folder.toPath())) {
            paths.filter(Files::isRegularFile)
                .filter(path -> isAudio(path.getFileName().toString()))
                .sorted()
                .forEach(path -> {
                  if (Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException();
                  found.add(path);
                });
          }
        List<Path> audioFiles = new ArrayList<>(found);
        int workers = workerCount(audioFiles.size());
        List<AudioTagData> loaded = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(
            workers, workerThreadFactory("library-tag-reader"));
        activeScanPool = pool;
        try (pool) {
          List<Future<AudioTagData>> tasks = new ArrayList<>();
          for (Path path : audioFiles)
            tasks.add(pool.submit(() -> {
              try {
                return AudioTagService.read(path.toFile());
              } catch (Exception ignored) {
                return null;
              }
            }));
          for (Future<AudioTagData> task : tasks) {
            AudioTagData data = task.get();
            if (data != null)
              loaded.add(data);
          }
        } finally {
          if (activeScanPool == pool)
            activeScanPool = null;
        }
        SwingUtilities.invokeLater(() -> {
          if (disposed) {
            libraryScanRunning.set(false);
            return;
          }
          for (AudioTagData data : loaded)
            if (modelRowForFile(data.file()) < 0)
              model.add(data, checkedByDefault);
          if (!checkedByDefault)
            model.selectAll(false);
          repaintCheckHeaders();
          libraryScanRunning.set(false);
          String scanSummary =
              "多目录扫描完成：" + loaded.size() + " 首，线程数 " + workers +
              (checkedByDefault ? "" : "，默认未勾选");
          statusDefault(scanSummary);
          selectFirstRowIfNeeded();
          int readFailures = Math.max(0, audioFiles.size() - loaded.size());
          appendLogs(List.of((readFailures == 0 ? "成功" : "警告") +
                             " | 音乐库扫描汇总 | 发现 " +
                             audioFiles.size() + " 个音频文件，载入 " +
                             loaded.size() + " 首，读取失败 " +
                             readFailures + " 首，使用 " + workers +
                             " 个线程，用时 " +
                             elapsedText(startedNanos)));
        });
      } catch (Exception ex) {
        SwingUtilities.invokeLater(() -> {
          libraryScanRunning.set(false);
          if (disposed)
            return;
          statusError("音乐文件夹扫描失败：" + ex.getMessage());
          appendLogs(List.of("异常 | 音乐库扫描 | " +
                             errorDetail(ex) + "，用时 " +
                             elapsedText(startedNanos)));
        });
      } finally {
        if (activeScanThread == Thread.currentThread())
          activeScanThread = null;
      }
    }, "library-scan");
    scanThread.setDaemon(true);
    activeScanThread = scanThread;
    scanThread.start();
  }
  private static boolean isAudio(String name) {
    String n = name.toLowerCase(java.util.Locale.ROOT);
    return n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".m4a") ||
        n.endsWith(".ogg") || n.endsWith(".opus") || n.endsWith(".wav") ||
        n.endsWith(".wma");
  }
  void openPlaybackFile(File file) {
    if (!followPlayback.isSelected() || file == null || !file.isFile())
      return;
    if (libraryScanRunning.get())
      return;
    sorter.setRowFilter(null);
    listSearch.setText("");
    int existing = modelRowForFile(file);
    if (existing >= 0) {
      selectModelRow(existing);
      return;
    }
    try {
      model.add(AudioTagService.read(file));
      selectModelRow(model.getRowCount() - 1);
      statusDefault("已跟随当前播放：" + file.getName());
    } catch (Exception ex) {
      statusWarning("无法载入当前播放歌曲：" + ex.getMessage());
    }
  }
  private void selectFirstRowIfNeeded() {
    if (model.getRowCount() > 0 && table.getSelectedRow() < 0)
      table.setRowSelectionInterval(0, 0);
  }
  private int modelRowForFile(File file) {
    if (file == null)
      return -1;
    Path wanted = file.toPath().toAbsolutePath().normalize();
    for (int i = 0; i < model.items.size(); i++)
      if (model.items.get(i).file().toPath().toAbsolutePath().normalize()
          .equals(wanted))
        return i;
    return -1;
  }

  private MusicSource selectedSource() {
    return (MusicSource)sourceBox.getSelectedItem();
  }
  private void matchSelected(MatchScope scope, MusicSource source,
                             boolean batch) {
    sync();
    int displayedRow = current;
    List<Integer> requestedRows =
        batch ? model.checkedRows()
              : (displayedRow >= 0 ? List.of(displayedRow) : List.of());
    if (requestedRows.isEmpty()) {
      statusDefault(batch ? "请先勾选需要匹配的歌曲"
                          : "请先选择一首歌曲");
      return;
    }
    List<Integer> rows = new ArrayList<>(requestedRows);
    int requestedCount = requestedRows.size();
    if (TagWorkbenchPlugin.skipCompleteTagsEnabled()) {
      List<Integer> skipped = rows.stream()
          .filter(row -> hasCompleteCoreTags(model.items.get(row)))
          .toList();
      rows.removeAll(skipped);
      if (!skipped.isEmpty()) {
        List<String> details = new ArrayList<>();
        for (int row : skipped) {
          details.add("跳过 | " + model.items.get(row).displayName() +
                      " | 已有完整标签，未请求标签源且不会写入匹配结果");
          if (batch && row < model.checked.size())
            model.checked.set(row, false);
        }
        appendLogs(details);
        repaintCheckHeaders();
      }
      if (rows.isEmpty()) {
        statusDefault("已跳过 " + skipped.size() +
                      " 首具有完整标签的歌曲");
        appendLogs(List.of("成功 | 匹配汇总 | 请求 " + requestedCount +
                           " 首，全部因已有完整标签而跳过"));
        return;
      }
    }
    if (!matchRunning.compareAndSet(false, true)) {
      statusDefault("已有匹配任务正在运行，请等待完成");
      return;
    }
    long token = matchSequence.incrementAndGet();
    latestMatchToken = token;
    for (int row : rows)
      rowMatchTokens.put(row, token);
    Map<Integer, AudioTagData> snapshots = new HashMap<>();
    for (int row : rows)
      snapshots.put(row, model.items.get(row));
    int plannedWorkers = workerCount(rows.size());
    int skippedCount = requestedCount - rows.size();
    long startedNanos = System.nanoTime();
    statusDefault(source.name() +
                  (batch ? "批量匹配：进度 0/" + rows.size() +
                               "，动态线程 " + plannedWorkers
                         : "正在匹配当前歌曲…"));
    if (batch)
      appendLogs(List.of("提示 | 批量匹配 | 共 " + rows.size() +
                         " 首，动态线程数 " + plannedWorkers));
    Thread matchThread = new Thread(() -> {
      Map<Integer, AudioTagData> updates = new ConcurrentHashMap<>();
      Set<Integer> incompleteRows = ConcurrentHashMap.newKeySet();
      Set<Integer> failedRows = ConcurrentHashMap.newKeySet();
      AtomicInteger failed = new AtomicInteger(), processed = new AtomicInteger();
      AtomicBoolean redLevelFailure = new AtomicBoolean();
      int workers = plannedWorkers;
      ExecutorService matchPool = Executors.newFixedThreadPool(
          workers, workerThreadFactory("tag-match-worker"));
      activeMatchPool = matchPool;
      try (matchPool) {
        if (disposed)
          return;
        List<Future<?>> matchTasks = new ArrayList<>();
        for (int i : rows)
          matchTasks.add(matchPool.submit(() -> {
        List<String> rowDetails = new ArrayList<>();
        AudioTagData d = snapshots.get(i);
        String song = d.displayName();
        try {
          String localTitle =
              d.title().isBlank()
                  ? d.file().getName().replaceFirst("\\.[^.]+$", "")
                  : d.title();
          MusicSource.Result m = MusicSources.searchValidated(
              source, localTitle, d.artist(), d.album(),
              durationMillis(d.duration()),
              scope == MatchScope.ALL || scope == MatchScope.LYRICS,
              candidate -> candidateMatches(
                  localTitle, d.artist(), durationMillis(d.duration()),
                  candidate.title(), candidate.artist(),
                  candidate.durationMillis()));
          if (m == null) {
            failed.incrementAndGet();
            failedRows.add(i);
            rowDetails.add("失败 | " + song + " | " + source.name() +
                        ("聚合源".equals(source.name())
                             ? " | 各来源结果均未同时通过标题、时长及艺术家或专辑确认"
                             : " | 未找到能由标题、时长及艺术家或专辑确认的歌曲"));
          } else if (titleMatch(localTitle, m.title()) == TitleMatch.NONE) {
            failed.incrementAndGet();
            failedRows.add(i);
            rowDetails.add("跳过 | " + song +
                        " | 平台搜索到歌曲「" + m.title() +
                        "」，但与本地标题「" + localTitle +
                        "」不匹配，未修改预览");
          } else if (!artistMatches(d.artist(), m.artist())) {
            failed.incrementAndGet();
            failedRows.add(i);
            rowDetails.add("跳过 | " + song +
                        " | 平台搜索到的艺术家「" + m.artist() +
                        "」与本地艺术家「" + d.artist() +
                        "」不匹配，未修改预览");
          } else if (!durationMatches(
                         durationMillis(d.duration()), m.durationMillis(),
                         false)) {
            failed.incrementAndGet();
            failedRows.add(i);
            rowDetails.add("跳过 | " + song +
                        " | 平台搜索到歌曲「" + m.title() +
                        "」，但时长无法确认是同一音频，未修改预览 | 本地 " +
                        d.duration() + "，平台结果 " +
                        formatDuration(m.durationMillis()));
          } else {
            boolean meta =
                scope == MatchScope.ALL || scope == MatchScope.METADATA;
            boolean lyric =
                (scope == MatchScope.ALL || scope == MatchScope.LYRICS) &&
                !"Apple Music".equals(source.name());
            boolean coverMatch =
                scope == MatchScope.ALL || scope == MatchScope.COVER;
            byte[] matchedCover = coverMatch ? m.cover() : d.cover();
            if (coverMatch &&
                (matchedCover == null || matchedCover.length == 0)) {
              String coverTitle =
                         m.title() == null || m.title().isBlank()
                             ? localTitle
                             : m.title(),
                     coverArtist =
                         m.artist() == null || m.artist().isBlank()
                             ? d.artist()
                             : m.artist();
              CoverFallback fallback = findFallbackCover(
                  source, (coverTitle + " " + coverArtist).trim(),
                  coverTitle, coverArtist, durationMillis(d.duration()));
              if (fallback != null) {
                matchedCover = fallback.bytes();
                rowDetails.add("补充 | " + song +
                               " | 主搜索结果没有封面，已从「" +
                               fallback.source() +
                               "」的其他搜索结果取得图片");
              }
            }
            String matchedLyrics = lyric ? m.lyrics() : d.lyrics();
            boolean lyricMissing =
                lyric && (matchedLyrics == null || matchedLyrics.isBlank());
            if (lyricMissing && scope == MatchScope.LYRICS) {
              failed.incrementAndGet();
              failedRows.add(i);
              incompleteRows.add(i);
              rowDetails.add("失败 | " + song + " | 歌词 | " + source.name() +
                          " 未返回任何歌词");
            } else if (scope == MatchScope.COVER && matchedCover == null) {
              failed.incrementAndGet();
              failedRows.add(i);
              rowDetails.add("失败 | " + song + " | 封面 | " + source.name() +
                          " 未返回图片");
            } else {
              if (lyricMissing) {
                matchedLyrics = d.lyrics();
                failed.incrementAndGet();
                failedRows.add(i);
                incompleteRows.add(i);
                rowDetails.add("失败 | " + song + " | 歌词 | " + source.name() +
                            " 未返回任何歌词，其他标签仍已预览");
              } else if (lyric && !isWordTimedLyrics(matchedLyrics))
                rowDetails.add("降级 | " + song +
                            " | 歌词 | 未找到逐字歌词，已自动使用普通歌词");
              if (scope == MatchScope.ALL && matchedCover == null) {
                failed.incrementAndGet();
                failedRows.add(i);
                incompleteRows.add(i);
                rowDetails.add("失败 | " + song + " | 封面 | " + source.name() +
                            " 未返回图片，其他标签仍已预览");
              }
              updates.put(
                  i,
                  new AudioTagData(
                      d.file(), meta ? keep(m.title(), d.title()) : d.title(),
                      meta ? keep(m.artist(), d.artist()) : d.artist(),
                      meta ? keep(m.album(), d.album()) : d.album(),
                      meta ? keep(m.albumArtist(), d.albumArtist())
                           : d.albumArtist(),
                      meta ? keep(m.lyricist(), d.lyricist()) : d.lyricist(),
                      meta ? keep(m.composer(), d.composer()) : d.composer(),
                      meta ? keep(m.year(), d.year()) : d.year(),
                      meta ? keepTrack(m.track(), d.track()) : d.track(),
                      meta ? keep(m.disc(), d.disc()) : d.disc(),
                      meta ? keep(m.genre(), d.genre()) : d.genre(),
                      matchedLyrics,
                      meta ? keep(m.comment(), d.comment()) : d.comment(),
                      coverMatch && matchedCover != null ? matchedCover
                                                        : d.cover(),
                      d.duration(), d.bitDepth(), d.bitrate()));
              if (!normalizeTitle(localTitle).equals(normalizeTitle(m.title())))
              rowDetails.add("相似匹配 | " + song + " | 已采用「" +
                             source.name() + "」搜索到的歌曲「" + m.title() +
                             "」，所选范围内的可用标签已更新到预览");
              String missing = missingFields(m, scope);
              if (!missing.isBlank()) {
                failed.incrementAndGet();
                failedRows.add(i);
                incompleteRows.add(i);
                rowDetails.add("部分填充 | " + song + " | " + source.name() +
                            " 未提供：" + missing);
              } else if (!incompleteRows.contains(i) &&
                       normalizeTitle(localTitle)
                           .equals(normalizeTitle(m.title())))
                rowDetails.add("成功 | " + song + " | " + source.name() +
                            " | 已取得所选范围内容");
            }
          }
        } catch (Exception ex) {
          failed.incrementAndGet();
          failedRows.add(i);
          rowDetails.add(
              "异常 | " + song + " | " + source.name() + " | " +
              ex.getClass().getSimpleName() + "：" +
              (ex.getMessage() == null ? "无详细信息" : ex.getMessage()));
        }
        if (rowDetails.stream().anyMatch(TagWorkbenchWindow::isRedLogLine))
          redLevelFailure.set(true);
        if (!rowDetails.isEmpty())
          SwingUtilities.invokeLater(() -> appendLogs(rowDetails));
        int done = processed.incrementAndGet();
        if (batch) {
          int failNow = failedRows.size(), successNow = updates.size(),
              percent = done * 100 / rows.size();
          SwingUtilities.invokeLater(() -> {
            if (token == latestMatchToken) {
              statusDefault(source.name() + "批量匹配：进度 " + done + "/" +
                            rows.size() + "，完成 " + percent + "%，成功 " +
                            successNow + "，失败 " + failNow);
            }
          });
        }
          }));
        for (Future<?> task : matchTasks)
          try {
            task.get();
          } catch (Exception ignored) {
          }
      } finally {
        if (activeMatchPool == matchPool)
          activeMatchPool = null;
        if (activeMatchThread == Thread.currentThread())
          activeMatchThread = null;
        if (disposed)
          matchRunning.set(false);
      }
      if (disposed)
        return;
      SwingUtilities.invokeLater(() -> {
        if (disposed) {
          matchRunning.set(false);
          return;
        }
        matchRunning.set(false);
        int applied = 0;
        for (var entry : updates.entrySet())
          if (Long.valueOf(token).equals(rowMatchTokens.get(entry.getKey())) &&
              entry.getKey() < model.items.size()) {
            model.items.set(entry.getKey(), entry.getValue());
            if (batch && scope == MatchScope.ALL &&
                !incompleteRows.contains(entry.getKey()) &&
                entry.getKey() < model.checked.size())
              model.checked.set(entry.getKey(), false);
            applied++;
          }
        if (applied > 0)
          model.fireTableDataChanged();
        boolean prioritizedFailures =
            batch && scope == MatchScope.ALL && !failedRows.isEmpty();
        if (prioritizedFailures) {
          model.prioritize(failedRows);
          sorter.setSortKeys(null);
          table.clearSelection();
          current = -1;
          if (model.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
            table.scrollRectToVisible(table.getCellRect(0, 0, true));
          }
        }
        repaintCheckHeaders();
        if (!prioritizedFailures && displayedRow >= 0 &&
            displayedRow == current &&
            updates.containsKey(displayedRow) &&
            Long.valueOf(token).equals(rowMatchTokens.get(displayedRow))) {
          int view = table.convertRowIndexToView(displayedRow);
          if (view >= 0) {
            current = -1;
            showRow(view);
            lyrics.revalidate();
            lyrics.repaint();
          }
        }
        rowMatchTokens.entrySet().removeIf(
            entry -> Long.valueOf(token).equals(entry.getValue()));
        if (token == latestMatchToken) {
          int failCount = failedRows.size() + (updates.size() - applied),
              complete = Math.max(0, applied - incompleteRows.size());
          String summary =
              "匹配完成：成功 " + complete + "，失败 " + failCount +
              (batch && scope == MatchScope.ALL
                   ? (failCount > 0
                          ? "，失败歌曲已置顶并保持勾选"
                          : "，已取消全部勾选")
                   : "，请预览后保存");
          if (redLevelFailure.get())
            statusError(summary);
          else if (failCount > 0)
            statusWarning(summary);
          else
            statusSuccess(summary);
          String level =
              redLevelFailure.get() ? "失败"
              : failCount > 0 ? "警告"
                              : "成功";
          appendLogs(List.of(level + " | 匹配汇总 | " + scopeText(scope) +
                             "，来源 " + source.name() + "，请求 " +
                             requestedCount + " 首，实际匹配 " +
                             rows.size() + " 首，完整 " + complete +
                             " 首，已预览 " + applied + " 首，失败 " +
                             failCount + " 首，跳过 " + skippedCount +
                             " 首，用时 " + elapsedText(startedNanos)));
          logHint.setText(logExpanded ? "down" : "up");
          }
      });
    }, "tag-match-" + token);
    matchThread.setDaemon(true);
    activeMatchThread = matchThread;
    matchThread.start();
  }
  private static String missingFields(MusicSource.Result m, MatchScope scope) {
    if (scope == MatchScope.LYRICS || scope == MatchScope.COVER)
      return "";
    List<String> missing = new ArrayList<>();
    if (m.title() == null || m.title().isBlank())
      missing.add("标题");
    if (m.artist() == null || m.artist().isBlank())
      missing.add("艺术家");
    if (m.album() == null || m.album().isBlank())
      missing.add("专辑");
    return String.join("、", missing);
  }
  private static String scopeText(MatchScope scope) {
    return switch (scope) {
      case ALL -> "全部标签";
      case METADATA -> "标签信息";
      case COVER -> "封面";
      case LYRICS -> "歌词";
    };
  }
  private record CoverFallback(String source, byte[] bytes) {}
  private static CoverFallback findFallbackCover(
      MusicSource selected, String keyword, String localTitle,
      String localArtist, long localDuration) {
    List<MusicSource> sources =
        "聚合源".equals(selected.name()) ? MusicSources.PROVIDERS
                                       : List.of(selected);
    for (MusicSource source : sources)
      try {
        for (MusicSources.CoverMatch result :
             MusicSources.coverMatches(source, keyword, 5))
          if (result.cover() != null && result.cover().length > 0 &&
              candidateMatches(localTitle, localArtist, localDuration,
                               result.title(), result.artist(),
                               result.durationMillis()))
            return new CoverFallback(result.source(), result.cover());
      } catch (Exception ignored) {
      }
    return null;
  }
  private static boolean hasCompleteCoreTags(AudioTagData data) {
    return data != null && !blank(data.title()) && !blank(data.artist()) &&
           !blank(data.album()) && !blank(data.lyrics()) &&
           data.cover() != null && data.cover().length > 0;
  }
  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
  private static String keep(String incoming, String old) {
    if (incoming == null)
      return old;
    String value = incoming.trim();
    return value.isBlank() || value.matches("(?i)^(?:0+(?:/0+)?|unknown|null|n/a|-)$")
        ? old
        : value;
  }
  private static String keepTrack(String incoming, String old) {
    String existing = old == null ? "" : old.trim();
    if (incoming == null)
      return existing;
    String value = incoming.trim();
    if (value.isBlank() ||
        value.matches("(?i)^(?:0+(?:/0+)?|unknown|null|n/a|-)$"))
      return existing;
    // 部分搜索接口会把无法确定的音轨号默认成 1；没有总轨数等佐证时，
    // 不用这个默认值覆盖一个原本为空的标签。
    if (existing.isBlank() && value.matches("^0*1(?:/0*1)?$"))
      return "";
    return value;
  }
  private static boolean isWordTimedLyrics(String value) {
    if (value == null || value.isBlank())
      return false;
    for (String line : value.split("\\R")) {
      java.util.regex.Matcher matcher =
          java.util.regex.Pattern.compile("\\[\\d{2,}:\\d{2}[.:]\\d{1,3}]")
              .matcher(line);
      int count = 0;
      while (matcher.find())
        if (++count >= 2)
          return true;
    }
    return false;
  }
  private enum TitleMatch { NONE, CORE, STRONG, EXACT }
  private static boolean candidateMatches(
      String localTitle, String localArtist, long localDuration,
      String remoteTitle, String remoteArtist, long remoteDuration) {
    TitleMatch title = titleMatch(localTitle, remoteTitle);
    return title != TitleMatch.NONE &&
           artistMatches(localArtist, remoteArtist) &&
           durationMatches(localDuration, remoteDuration, false);
  }
  private static TitleMatch titleMatch(String local, String remote) {
    String a = normalizeTitle(local), b = normalizeTitle(remote);
    if (a.isBlank() || b.isBlank())
      return TitleMatch.NONE;
    if (a.equals(b))
      return TitleMatch.EXACT;
    String coreA = coreTitle(local), coreB = coreTitle(remote);
    if (usableCore(coreA) && usableCore(coreB) && coreA.equals(coreB))
      return TitleMatch.CORE;
    if (textSimilar(a, b))
      return TitleMatch.STRONG;
    return TitleMatch.NONE;
  }
  private static boolean textSimilar(String a, String b) {
    if (a.isBlank() || b.isBlank())
      return false;
    int shorter = Math.min(a.length(), b.length());
    boolean hasCjk = (a + b).codePoints().anyMatch(
        code -> Character.UnicodeScript.of(code) ==
                    Character.UnicodeScript.HAN ||
                Character.UnicodeScript.of(code) ==
                    Character.UnicodeScript.HIRAGANA ||
                Character.UnicodeScript.of(code) ==
                    Character.UnicodeScript.KATAKANA);
    int minimum = hasCjk ? 2 : 4;
    if (shorter >= minimum && (a.contains(b) || b.contains(a)))
      return true;
    int common = 0;
    for (int i = 0; i < a.length(); i++)
      if (b.indexOf(a.charAt(i)) >= 0)
        common++;
    return shorter >= minimum && common >= shorter * .72;
  }
  private static String coreTitle(String value) {
    if (value == null)
      return "";
    int cut = value.length();
    for (char marker : new char[] {'(', '（', '[', '【'}) {
      int at = value.indexOf(marker);
      if (at >= 0)
        cut = Math.min(cut, at);
    }
    java.util.regex.Matcher mediaSuffix = java.util.regex.Pattern.compile(
        "[-—–|]\\s*[《『【]").matcher(value);
    if (mediaSuffix.find())
      cut = Math.min(cut, mediaSuffix.start());
    return normalizeTitle(value.substring(0, cut));
  }
  private static boolean usableCore(String value) {
    if (value == null || value.isBlank())
      return false;
    boolean hasCjk = value.codePoints().anyMatch(
        code -> Character.UnicodeScript.of(code) ==
                    Character.UnicodeScript.HAN ||
                Character.UnicodeScript.of(code) ==
                    Character.UnicodeScript.HIRAGANA ||
                Character.UnicodeScript.of(code) ==
                    Character.UnicodeScript.KATAKANA);
    return value.length() >= (hasCjk ? 2 : 4);
  }
  private static boolean durationMatches(long local, long remote,
                                         boolean coreOnly) {
    if (local <= 0 || remote <= 0)
      return !coreOnly;
    long difference = Math.abs(local - remote);
    long close = Math.max(5_000, Math.min(10_000, local * 3 / 100));
    if (coreOnly)
      return difference <= close;
    long maximum = Math.max(12_000, Math.min(20_000, local * 6 / 100));
    return difference <= maximum;
  }
  private static String formatDuration(long millis) {
    if (millis <= 0)
      return "未知";
    long seconds = Math.round(millis / 1000d);
    return String.format(java.util.Locale.ROOT, "%d:%02d",
                         seconds / 60, seconds % 60);
  }
  private static boolean artistMatches(String local, String remote) {
    if (local == null || local.isBlank())
      return true;
    if (remote == null || remote.isBlank())
      return false;
    String a = normalizeTitle(local), b = normalizeTitle(remote);
    if (a.equals(b))
      return true;
    int shorter = Math.min(a.length(), b.length());
    if (shorter >= 2 && (a.contains(b) || b.contains(a)))
      return true;
    int common = 0;
    for (int i = 0; i < a.length(); i++)
      if (b.indexOf(a.charAt(i)) >= 0)
        common++;
    return shorter >= 2 && common >= shorter * .8;
  }
  private static int workerCount(int tasks) {
    int processors = Math.max(
        1, Runtime.getRuntime().availableProcessors());
    return Math.max(1, Math.min(tasks, Math.min(processors, 32)));
  }
  private static java.util.concurrent.ThreadFactory workerThreadFactory(
      String prefix) {
    AtomicInteger sequence = new AtomicInteger();
    return runnable -> {
      Thread thread =
          new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
  private static String normalizeTitle(String value) {
    return value == null
        ? ""
        : value.toLowerCase(java.util.Locale.ROOT)
              .replaceAll("\\([^)]*(?:ver|version|版|edit)[^)]*\\)", "")
              .replaceAll(
                  "[\\p{Punct}\\p{IsPunctuation}\\p{Z}\\s_]+", "");
  }
  private JMenu sourceMenu(String label, MatchScope scope, boolean batch) {
    return sourceMenu(label, scope, batch, true);
  }
  private JMenu sourceMenu(String label, MatchScope scope, boolean batch,
                           boolean allowAggregate) {
    JMenu sources = new JMenu(label);
    styleMenuItem(sources);
    stylePopup(sources.getPopupMenu());
    for (MusicSource source : MusicSources.ALL) {
      if (!allowAggregate && "聚合源".equals(source.name()))
        continue;
      if (scope == MatchScope.LYRICS &&
          "Apple Music".equals(source.name()))
        continue;
      JMenuItem item = new JMenuItem(source.name());
      styleMenuItem(item);
      item.addActionListener(e -> matchSelected(scope, source, batch));
      sources.add(item);
    }
    return sources;
  }
  private static JPopupMenu popupMenu() {
    JPopupMenu menu = new JPopupMenu();
    stylePopup(menu);
    return menu;
  }
  private static void stylePopup(JPopupMenu menu) {
    menu.setLightWeightPopupEnabled(false);
    menu.putClientProperty("Popup.forceHeavyWeight", true);
    menu.putClientProperty("Popup.borderCornerRadius", 14);
    menu.putClientProperty("Popup.roundedBorderWidth", 1f);
    menu.putClientProperty("Popup.dropShadowPainted", true);
  }
  private static void styleMenuItem(JMenuItem item) {
    item.putClientProperty("JComponent.roundRect", true);
    item.putClientProperty("FlatLaf.style", "selectionArc: 12");
  }
  private static void styleComboPopup(JComboBox<?> combo) {
    combo.putClientProperty("Popup.forceHeavyWeight", true);
    combo.putClientProperty("Popup.borderCornerRadius", 14);
    combo.putClientProperty("Popup.roundedBorderWidth", 1f);
    combo.putClientProperty("Popup.dropShadowPainted", true);
  }
  private void installListMenu(JComponent target) {
    JPopupMenu menu = popupMenu();
    menu.add(sourceMenu("匹配全部", MatchScope.ALL, true));
    menu.add(sourceMenu("匹配封面", MatchScope.COVER, true));
    menu.add(sourceMenu("匹配歌词", MatchScope.LYRICS, true));
    menu.addSeparator();
    JMenuItem clear = new JMenuItem("清空列表");
    styleMenuItem(clear);
    clear.addActionListener(e -> clearList());
    menu.add(clear);
    applyPopup(target, menu);
  }
  private void installSourceMenu(JComponent target, MatchScope scope) {
    JPopupMenu menu = popupMenu();
    String label = switch (scope) {
      case ALL -> "匹配全部";
      case COVER -> "匹配封面";
      case LYRICS -> "匹配歌词";
      case METADATA -> "匹配标签";
    };
    menu.add(sourceMenu(label, scope, false,
                        scope != MatchScope.COVER &&
                            scope != MatchScope.LYRICS));
    if (scope == MatchScope.COVER) {
      menu.addSeparator();
      JMenuItem importItem = new JMenuItem("导入封面");
      styleMenuItem(importItem);
      importItem.addActionListener(e -> importCover());
      menu.add(importItem);
      JMenuItem exportItem = new JMenuItem("导出封面");
      styleMenuItem(exportItem);
      exportItem.addActionListener(e -> exportCover());
      menu.add(exportItem);
    }
    applyPopup(target, menu);
  }
  private static void applyPopup(Component component, JPopupMenu menu) {
    if (component instanceof JComponent jc) {
      jc.setComponentPopupMenu(menu);
      jc.setInheritsPopupMenu(true);
    }
    if (component instanceof Container container)
      for (Component child : container.getComponents())
        applyPopup(child, menu);
  }
  private enum MatchScope { ALL, METADATA, COVER, LYRICS }

  private void importCover() {
    if (current < 0 || current >= model.items.size()) {
      statusDefault("请先选择一首歌曲");
      return;
    }
    JFileChooser chooser =
        new JFileChooser(model.items.get(current).file().getParentFile());
    chooser.setDialogTitle("导入封面");
    chooser.setFileFilter(new FileNameExtensionFilter("图片文件", "jpg", "jpeg",
                                                      "png", "webp", "gif"));
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
      return;
    long startedNanos = System.nanoTime();
    try {
      byte[] bytes = Files.readAllBytes(chooser.getSelectedFile().toPath());
      ImageIcon image = new ImageIcon(bytes);
      if (image.getIconWidth() <= 0 || image.getIconHeight() <= 0)
        throw new IllegalArgumentException("无法识别该图片");
      sync();
      AudioTagData d = model.items.get(current);
      model.items.set(
          current, new AudioTagData(d.file(), d.title(), d.artist(), d.album(),
                                    d.albumArtist(), d.lyricist(), d.composer(),
                                    d.year(), d.track(), d.disc(), d.genre(),
                                    d.lyrics(), d.comment(), bytes,
                                    d.duration(), d.bitDepth(), d.bitrate()));
      cover.setCover(bytes);
      coverInfo.setText(image.getIconWidth() + " × " + image.getIconHeight() +
                        "px    " + formatBytes(bytes.length));
      statusDefault("封面已导入，点击保存当前或保存选中后写入文件");
      appendLogs(List.of("成功 | 封面导入 | " + d.displayName() + " | " +
                         chooser.getSelectedFile().getName() + "，" +
                         imageInfo(bytes) + "，用时 " +
                         elapsedText(startedNanos)));
    } catch (Exception ex) {
      statusError("封面导入失败：" + ex.getMessage());
      appendLogs(List.of("异常 | 封面导入 | " +
                         chooser.getSelectedFile().getName() + " | " +
                         errorDetail(ex) + "，用时 " +
                         elapsedText(startedNanos)));
    }
  }
  private void exportCover() {
    if (current < 0 || current >= model.items.size() ||
        model.items.get(current).cover() == null) {
      statusDefault("当前歌曲没有可导出的封面");
      return;
    }
    AudioTagData d = model.items.get(current);
    String base = d.file().getName().replaceFirst("\\.[^.]+$", "");
    JFileChooser chooser = new JFileChooser(d.file().getParentFile());
    chooser.setDialogTitle("导出封面");
    chooser.setSelectedFile(
        new File(d.file().getParentFile(),
                 base + "-cover." + coverExtension(d.cover())));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
      return;
    long startedNanos = System.nanoTime();
    try {
      Files.write(chooser.getSelectedFile().toPath(), d.cover());
      statusDefault("封面已导出：" +
                    chooser.getSelectedFile().getAbsolutePath());
      appendLogs(List.of("成功 | 封面导出 | " + d.displayName() + " | " +
                         chooser.getSelectedFile().getAbsolutePath() +
                         "，用时 " + elapsedText(startedNanos)));
    } catch (Exception ex) {
      statusError("封面导出失败：" + ex.getMessage());
      appendLogs(List.of("异常 | 封面导出 | " + d.displayName() + " | " +
                         errorDetail(ex) + "，用时 " +
                         elapsedText(startedNanos)));
    }
  }
  private static String coverExtension(byte[] bytes) {
    if (bytes.length >= 8 && bytes[0] == (byte)0x89 && bytes[1] == 0x50 &&
        bytes[2] == 0x4e && bytes[3] == 0x47)
      return "png";
    if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' &&
        bytes[2] == 'F' && bytes[3] == 'F' && bytes[8] == 'W' &&
        bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P')
      return "webp";
    return "jpg";
  }
  private static String imageInfo(byte[] bytes) {
    if (bytes == null || bytes.length == 0)
      return "无封面";
    ImageIcon image = new ImageIcon(bytes);
    return image.getIconWidth() + " × " + image.getIconHeight() + " px  ·  " +
        formatBytes(bytes.length);
  }
  private void showRow(int viewRow) {
    if (viewRow < 0)
      return;
    int r = table.convertRowIndexToModel(viewRow);
    if (r < 0 || r >= model.items.size())
      return;
    sync();
    current = r;
    AudioTagData d = model.items.get(r);
    title.setText(d.title());
    artist.setText(d.artist());
    album.setText(d.album());
    albumArtist.setText(d.albumArtist());
    lyricist.setText(d.lyricist());
    composer.setText(d.composer());
    year.setText(d.year());
    track.setText(d.track());
    disc.setText(d.disc());
    genre.setText(d.genre());
    for (JTextField field :
         new JTextField[] {title, artist, album, albumArtist, lyricist,
                           composer, year, track, disc, genre})
      field.setCaretPosition(0);
    lyrics.setText(d.lyrics());
    lyrics.setCaretPosition(0);
    comment.setText(d.comment());
    comment.setCaretPosition(0);
    cover.setCover(d.cover());
    if (d.cover() == null)
      coverInfo.setText("");
    else {
      ImageIcon original = new ImageIcon(d.cover());
      coverInfo.setText(original.getIconWidth() + " × " +
                        original.getIconHeight() + "px    " +
                        formatBytes(d.cover().length));
    }
    SmoothScrollSupport.resetToStart(metadataScroll);
    SmoothScrollSupport.resetToStart(lyricScroll);
    SwingUtilities.invokeLater(() -> {
      SmoothScrollSupport.resetToStart(metadataScroll);
      SmoothScrollSupport.resetToStart(lyricScroll);
    });
  }
  private static String formatBytes(long bytes) {
    if (bytes >= 1024L * 1024L)
      return String.format(java.util.Locale.ROOT, "%.1fMB",
                           bytes / (1024d * 1024d));
    return String.format(java.util.Locale.ROOT, "%.0fKB",
                         Math.max(1, bytes / 1024d));
  }
  private void applyListFilter() {
    String q = listSearch.getText().trim().toLowerCase(java.util.Locale.ROOT);
    if (q.isEmpty())
      sorter.setRowFilter(null);
    else
      sorter.setRowFilter(new RowFilter<>() {public boolean include(Entry<? extends SongModel,? extends Integer> entry){
          AudioTagData d = model.items.get(entry.getIdentifier());
          String tags =
              String.join("\n", d.title(), d.artist(), d.album(),
                          d.albumArtist(), d.lyricist(), d.composer(), d.year(),
                          d.track(), d.disc(), d.genre(), d.comment());
          return tags.toLowerCase(java.util.Locale.ROOT).contains(q);
        }
      });
    table.clearSelection();
    statusDefault("筛选结果：" + table.getRowCount() + " 首");
  }
  private void clearSearch() {
    listSearch.setText("");
    sorter.setRowFilter(null);
    statusDefault("已恢复完整歌曲列表：" + model.getRowCount() + " 首");
    listSearch.requestFocusInWindow();
  }
  private void clearList() {
    sync();
    int removed = model.getRowCount();
    resetListForReload();
    statusDefault("歌曲列表已清空");
    appendLogs(List.of("提示 | 歌曲列表 | 已从临时列表清除 " + removed +
                       " 首歌曲，未删除音频文件"));
  }
  private void resetListForReload() {
    model.clear();
    repaintCheckHeaders();
    sorter.setSortKeys(null);
    sorter.setRowFilter(null);
    listSearch.setText("");
    table.clearSelection();
    current = -1;
    for (JTextField f :
         new JTextField[] {title, artist, album, albumArtist, lyricist,
                           composer, year, track, disc, genre})
      f.setText("");
    lyrics.setText("");
    comment.setText("");
    cover.setCover(null);
    coverInfo.setText("");
    rowMatchTokens.clear();
  }
  private void hideSongTableToolTip() {
    hideTableToolTip(table);
  }
  private static void hideTableToolTip(JTable target) {
    ToolTipManager.sharedInstance().mouseExited(
        new java.awt.event.MouseEvent(
            target, java.awt.event.MouseEvent.MOUSE_EXITED,
            System.currentTimeMillis(), 0, -1, -1, 0, false));
  }
  private void selectModelRow(int modelRow) {
    int view = table.convertRowIndexToView(modelRow);
    if (view < 0)
      return;
    table.setRowSelectionInterval(view, view);
    table.scrollRectToVisible(table.getCellRect(view, 0, true));
  }
  private void sync() {
    if (current >= 0 && current < model.items.size()) {
      AudioTagData d = model.items.get(current);
      model.items.set(
          current,
          new AudioTagData(d.file(), title.getText(), artist.getText(),
                           album.getText(), albumArtist.getText(),
                           lyricist.getText(), composer.getText(),
                           year.getText(), track.getText(), disc.getText(),
                           genre.getText(), lyrics.getText(), comment.getText(),
                           d.cover(), d.duration(), d.bitDepth(), d.bitrate()));
    }
  }
  private void saveCurrent() {
    sync();
    if (current < 0)
      return;
    AudioTagData data = model.items.get(current);
    long startedNanos = System.nanoTime();
    try {
      AudioTagService.write(data);
      statusSuccess("保存成功：" + data.file().getName());
      appendLogs(List.of("成功 | 标签写入 | " + data.displayName() +
                         " | 已写入 " + data.file().getName() + "，用时 " +
                         elapsedText(startedNanos)));
    } catch (Exception ex) {
      statusError("保存失败：" + ex.getMessage());
      appendLogs(List.of("异常 | 标签写入 | " + data.displayName() + " | " +
                         errorDetail(ex) + "，用时 " +
                         elapsedText(startedNanos)));
    }
  }
  private void saveSelected() {
    sync();
    List<Integer> selectedRows = model.checkedRows();
    if (selectedRows.isEmpty()) {
      statusDefault("请先勾选需要保存的歌曲");
      return;
    }
    long startedNanos = System.nanoTime();
    int ok = 0, fail = 0;
    for (int i : selectedRows)
      try {
        AudioTagService.write(model.items.get(i));
        ok++;
      } catch (Exception ex) {
        fail++;
        appendLogs(List.of("异常 | 标签写入 | " +
                           model.items.get(i).displayName() + " | " +
                           errorDetail(ex)));
      }
    String summary = "批量保存完成：成功 " + ok + "，失败 " + fail;
    if (fail == 0)
      statusSuccess(summary);
    else
      statusError(summary);
    appendLogs(List.of((fail == 0 ? "成功" : "失败") +
                       " | 标签写入汇总 | 请求 " + selectedRows.size() +
                       " 首，成功 " + ok + " 首，失败 " + fail +
                       " 首，用时 " + elapsedText(startedNanos)));
  }

  private static JButton button(String s, Runnable action, boolean primary) {
    JButton b = new JButton(s);
    b.setFont(b.getFont().deriveFont(12f));
    b.setMargin(new Insets(5, 9, 5, 9));
    b.setFocusPainted(false);
    b.putClientProperty("FlatLaf.style", "arc: 10");
    b.putClientProperty("JComponent.minimumWidth", 60);
    b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    if (primary) {
      b.setBackground(ACCENT);
      b.setForeground(Color.WHITE);
    }
    b.addActionListener(e -> action.run());
    return b;
  }
  private static void equalizeButtonWidths(JButton... buttons) {
    int width = 0, height = 0;
    for (JButton button : buttons) {
      width = Math.max(width, button.getPreferredSize().width);
      height = Math.max(height, button.getPreferredSize().height);
    }
    Dimension size = new Dimension(width, height);
    for (JButton button : buttons) {
      button.putClientProperty("JComponent.minimumWidth", width);
      button.setPreferredSize(size);
      button.setMinimumSize(size);
      button.setMaximumSize(size);
    }
  }
  private static JTextField input() {
    JTextField f = new JTextField();
    f.putClientProperty("FlatLaf.style", "arc: 10");
    f.putClientProperty("JTextField.placeholderText", "");
    f.setMargin(new Insets(6, 7, 6, 7));
    int height = Math.max(28, f.getPreferredSize().height);
    f.setPreferredSize(new Dimension(120, height));
    f.setMinimumSize(new Dimension(0, height));
    f.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    return f;
  }
  private static JTextArea textArea(int rows) {
    JTextArea a = new JTextArea(rows, 8);
    a.setMinimumSize(new Dimension(0, 40));
    a.setLineWrap(true);
    a.setWrapStyleWord(true);
    a.putClientProperty("FlatLaf.style", "arc: 10");
    a.setBorder(new EmptyBorder(8, 8, 8, 8));
    return a;
  }
  private static final class PlaceholderTextArea extends JTextArea {
    private final String placeholder;
    PlaceholderTextArea(String placeholder, int rows) {
      super(rows, 8);
      this.placeholder = placeholder;
      setMinimumSize(new Dimension(0, 40));
      setLineWrap(true);
      setWrapStyleWord(true);
      putClientProperty("FlatLaf.style", "arc: 10");
      setBorder(new EmptyBorder(8, 8, 8, 8));
    }
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      if (!getText().isEmpty())
        return;
      Graphics2D g2 = (Graphics2D)g.create();
      g2.setColor(UIManager.getColor("Label.disabledForeground"));
      FontMetrics fm = g2.getFontMetrics();
      int x = (getWidth() - fm.stringWidth(placeholder)) / 2;
      int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
      g2.drawString(placeholder, Math.max(8, x),
                    Math.max(fm.getAscent() + 8, y));
      g2.dispose();
    }
  }
  private static final class LogHandle extends JComponent {
    private final JLabel status;
    private final JLabel hint;
    LogHandle(JLabel status, JLabel hint, Runnable toggle) {
      this.status = status;
      this.hint = hint;
      setPreferredSize(new Dimension(0, 24));
      setMinimumSize(new Dimension(0, 24));
      status.addPropertyChangeListener("text", e -> repaint());
      status.addPropertyChangeListener("foreground", e -> repaint());
      hint.addPropertyChangeListener("text", e -> repaint());
      addMouseListener(new java.awt.event.MouseAdapter() {
        public void mouseClicked(java.awt.event.MouseEvent e) {
          if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2)
            toggle.run();
        }
      });
    }
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D)g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                          RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                          RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                          RenderingHints.VALUE_FRACTIONALMETRICS_ON);
      g2.setFont(status.getFont());
      FontMetrics statusMetrics = g2.getFontMetrics();
      String text = status.getText() == null ? "" : status.getText();
      boolean down = "down".equals(hint.getText());
      int gap = 5;
      int arrowWidth = 12;
      int total = arrowWidth + gap + statusMetrics.stringWidth(text) + gap +
                  arrowWidth;
      int x = Math.max(0, (getWidth() - total) / 2);
      int baseline = (getHeight() - statusMetrics.getHeight()) / 2 +
                     statusMetrics.getAscent();
      g2.setColor(status.getForeground());
      drawDoubleChevron(g2, x, getHeight() / 2, down);
      g2.setFont(status.getFont());
      g2.setColor(status.getForeground());
      int textX = x + arrowWidth + gap;
      g2.drawString(text, textX, baseline);
      g2.setColor(status.getForeground());
      drawDoubleChevron(g2,
                        textX + statusMetrics.stringWidth(text) + gap,
                        getHeight() / 2, down);
      g2.dispose();
    }
    private static void drawDoubleChevron(Graphics2D g, int x, int centerY,
                                          boolean down) {
      g.setStroke(new BasicStroke(1.25f, BasicStroke.CAP_ROUND,
                                  BasicStroke.JOIN_ROUND));
      for (int offset : new int[] {-3, 2}) {
        int y = centerY + offset;
        if (down) {
          g.drawLine(x + 1, y - 2, x + 6, y + 2);
          g.drawLine(x + 6, y + 2, x + 11, y - 2);
        } else {
          g.drawLine(x + 1, y + 2, x + 6, y - 2);
          g.drawLine(x + 6, y - 2, x + 11, y + 2);
        }
      }
    }
  }
  private static final class FooterOverlayHost extends JLayeredPane {
    private final JComponent content;
    private final JComponent footer;
    FooterOverlayHost(JComponent content, JComponent footer) {
      this.content = content;
      this.footer = footer;
      setOpaque(false);
      add(content, JLayeredPane.DEFAULT_LAYER);
      add(footer, JLayeredPane.PALETTE_LAYER);
    }
    public void doLayout() {
      int footerHeight = Math.max(18, footer.getPreferredSize().height);
      content.setBounds(0, 0, getWidth(),
                        Math.max(0, getHeight() - footerHeight));
      footer.setBounds(0, Math.max(0, getHeight() - footerHeight), getWidth(),
                       Math.min(getHeight(), footerHeight));
      layoutComponentTree(content);
      layoutComponentTree(footer);
    }
  }
  private static final class OverlayButtonHost extends JLayeredPane {
    private final JComponent content;
    private JButton button;
    private JComponent buttonAnchor;
    OverlayButtonHost(JComponent content) {
      this.content = content;
      setOpaque(false);
      add(content, JLayeredPane.DEFAULT_LAYER);
    }
    void setOverlayButton(JButton value) {
      if (button != null && button.getParent() == this)
        remove(button);
      if (value.getParent() != null)
        value.getParent().remove(value);
      button = value;
      add(button, JLayeredPane.PALETTE_LAYER);
      revalidate();
      repaint();
    }
    void setButtonAnchor(JComponent value) {
      buttonAnchor = value;
      revalidate();
      repaint();
    }
    void forceOverlayLayout() {
      doLayout();
      if (button != null) {
        button.setVisible(true);
        button.repaint();
      }
      repaint();
    }
    public void doLayout() {
      content.setBounds(0, 0, getWidth(), getHeight());
      layoutComponentTree(content);
      if (button == null)
        return;
      Dimension size = button.getPreferredSize();
      Rectangle anchor =
          buttonAnchor != null && buttonAnchor.getParent() != null
              ? SwingUtilities.convertRectangle(buttonAnchor.getParent(),
                                                buttonAnchor.getBounds(), this)
              : new Rectangle(0, 0, getWidth(), getHeight());
      button.setBounds(
          Math.max(anchor.x + 6,
                   anchor.x + anchor.width - size.width - 24),
          anchor.y + 10, size.width, size.height);
    }
  }
  private static final class LyricCandidateModel extends AbstractTableModel {
    private List<MusicSources.LyricSearchMatch> rows = List.of();
    private static final String[] COLUMNS =
        {"歌曲", "艺术家", "专辑", "时长", "来源"};
    void setRows(List<MusicSources.LyricSearchMatch> value) {
      rows = List.copyOf(value);
      fireTableDataChanged();
    }
    MusicSources.LyricSearchMatch rowAt(int row) { return rows.get(row); }
    public int getRowCount() { return rows.size(); }
    public int getColumnCount() { return COLUMNS.length; }
    public String getColumnName(int column) { return COLUMNS[column]; }
    public Class<?> getColumnClass(int column) { return String.class; }
    public Object getValueAt(int row, int column) {
      MusicSources.LyricSearchMatch item = rows.get(row);
      return switch (column) {
        case 0 -> item.title();
        case 1 -> item.artist();
        case 2 -> item.album();
        case 3 -> item.duration();
        default -> item.source();
      };
    }
  }
  private static final class FadingOverlayButton extends JButton {
    private boolean hovered;
    FadingOverlayButton(String text, Runnable action) {
      super(text);
      putClientProperty("FlatLaf.style", "arc: 10");
      addActionListener(event -> action.run());
      addMouseListener(new java.awt.event.MouseAdapter() {
        public void mouseEntered(java.awt.event.MouseEvent event) {
          hovered = true;
          repaint();
        }
        public void mouseExited(java.awt.event.MouseEvent event) {
          hovered = false;
          repaint();
        }
      });
    }
    public void paint(Graphics graphics) {
      Graphics2D g = (Graphics2D)graphics.create();
      g.setComposite(AlphaComposite.SrcOver.derive(hovered ? .96f : .48f));
      super.paint(g);
      g.dispose();
    }
  }
  private static JScrollPane scroll(Component c) {
    RoundedScrollPane s = new RoundedScrollPane(c);
    s.setBorder(new RoundedBorder(new Color(218, 221, 227), 12));
    s.putClientProperty("JComponent.roundRect", true);
    s.getViewport().setOpaque(false);
    if (c instanceof JTextComponent text)
      text.setOpaque(false);
    s.getVerticalScrollBar().setOpaque(false);
    s.getHorizontalScrollBar().setOpaque(false);
    s.getVerticalScrollBar().putClientProperty("JComponent.roundRect", true);
    s.getHorizontalScrollBar().putClientProperty("JComponent.roundRect", true);
    s.getVerticalScrollBar().setUnitIncrement(12);
    s.getVerticalScrollBar().setBlockIncrement(180);
    s.getHorizontalScrollBar().setUnitIncrement(12);
    SmoothScrollSupport.install(s);
    return s;
  }
  private static JComponent roundedTextArea(JTextArea area) {
    JPanel p = new JPanel(new BorderLayout()) {
      protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D)g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(UIManager.getColor("TextArea.background"));
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
        g2.dispose();
      }
    };
    p.setOpaque(false);
    p.setBorder(new RoundedBorder(new Color(218, 221, 227), 14));
    area.setOpaque(false);
    p.add(area);
    int height = Math.max(58, area.getPreferredSize().height);
    p.setPreferredSize(new Dimension(120, height));
    p.setMinimumSize(new Dimension(0, height));
    p.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    return p;
  }
  private static Border titled(String t) {
    TitledBorder title =
        new TitledBorder(new RoundedBorder(new Color(220, 223, 228), 14), t,
                         TitledBorder.CENTER, TitledBorder.TOP);
    return new CompoundBorder(title, new EmptyBorder(6, 8, 8, 8));
  }
  private static int row(JPanel p, GridBagConstraints c, int r, String n,
                         JComponent v) {
    c.gridwidth = 1;
    c.gridx = 0;
    c.gridy = r;
    c.weightx = 0;
    p.add(new JLabel(n), c);
    c.gridx = 1;
    c.weightx = 1;
    p.add(v, c);
    return r + 1;
  }
  private static final class WorkbenchLook {
    private static final String[] KEYS = {
        "Panel.background", "Viewport.background", "RootPane.background",
        "RootPane.border", "TitlePane.background",
        "TitlePane.inactiveBackground", "TitlePane.unifiedBackground",
        "TextField.background", "TextArea.background", "Table.background",
        "Table.foreground", "Table.selectionForeground",
        "Table.selectionBackground", "TableHeader.background",
        "ScrollPane.background", "ScrollBar.track",
        "ScrollBar.hoverTrackColor", "ScrollBar.thumb",
        "ScrollBar.hoverThumbColor", "ScrollBar.trackArc",
        "ScrollBar.thumbArc", "ScrollBar.showButtons",
        "PopupMenu.background", "PopupMenu.borderCornerRadius",
        "PopupMenu.roundedBorderWidth", "PopupMenu.dropShadowPainted",
        "Popup.borderCornerRadius", "Popup.roundedBorderWidth",
        "Popup.dropShadowPainted", "Popup.forceHeavyWeight",
        "ComboBox.borderCornerRadius", "ComboBox.roundedBorderWidth",
        "MenuItem.selectionArc",
        "MenuItem.margin", "ComboBox.buttonStyle", "defaultFont",
        "Button.arc", "Component.arc", "TextComponent.arc",
        "ScrollPane.arc", "ScrollBar.width", "Table.rowHeight",
        "Component.focusWidth", "Button.innerFocusWidth",
        "Label.disabledForeground", "Button.disabledText",
        "TextField.caretForeground", "TextField.placeholderForeground",
        "FormattedTextField.caretForeground",
        "PasswordField.caretForeground", "TextArea.caretForeground",
        "TextPane.caretForeground", "EditorPane.caretForeground",
        "ComboBox.selectionForeground", "List.selectionForeground",
        "Menu.selectionForeground", "MenuItem.selectionForeground",
        "CheckBoxMenuItem.selectionForeground",
        "RadioButtonMenuItem.selectionForeground",
        "ProgressBar.selectionForeground"};
    private static final Map<String, Object> previousValues = new HashMap<>();
    private static LookAndFeel previousLook;
    private static int references;
    static synchronized void acquire() {
      if (references++ > 0)
        return;
      previousLook = UIManager.getLookAndFeel();
      previousValues.clear();
      for (String key : KEYS)
        previousValues.put(key, UIManager.get(key));
      for (String key : FONT_UI_KEYS)
        previousValues.putIfAbsent(key, UIManager.get(key));
      for (String key : TEXT_UI_KEYS)
        previousValues.putIfAbsent(key, UIManager.get(key));
      applyLook();
    }
    static synchronized void release() {
      if (references <= 0 || --references > 0)
        return;
      try {
        if (previousLook != null)
          UIManager.setLookAndFeel(previousLook);
      } catch (UnsupportedLookAndFeelException ignored) {
      }
      for (String key : KEYS)
        UIManager.put(key, previousValues.get(key));
      for (String key : FONT_UI_KEYS)
        UIManager.put(key, previousValues.get(key));
      for (String key : TEXT_UI_KEYS)
        UIManager.put(key, previousValues.get(key));
      previousValues.clear();
      previousLook = null;
    }
  }
  private static void applyLook() {
    if (dark)
      FlatDarkLaf.setup();
    else
      FlatLightLaf.setup();
    Color bg = dark ? new Color(32, 32, 32) : new Color(243, 243, 243);
    Color field = dark ? new Color(39, 39, 39) : new Color(249, 249, 249);
    Color selected = dark ? new Color(53, 67, 88) : new Color(222, 232, 249);
    Color text = dark ? new Color(235, 235, 235) : new Color(32, 32, 32);
    Color selectedText =
        dark ? new Color(245, 245, 245) : new Color(24, 24, 24);
    Color disabledText =
        dark ? new Color(150, 150, 150) : new Color(112, 112, 112);
    Color placeholder =
        dark ? new Color(145, 145, 145) : new Color(125, 125, 125);
    UIManager.put("Panel.background", bg);
    UIManager.put("Viewport.background", bg);
    UIManager.put("RootPane.background", bg);
    UIManager.put("RootPane.border", new EmptyBorder(0, 0, 0, 0));
    UIManager.put("TitlePane.background", bg);
    UIManager.put("TitlePane.inactiveBackground", bg);
    UIManager.put("TitlePane.unifiedBackground", true);
    UIManager.put("TextField.background", field);
    UIManager.put("TextArea.background", field);
    UIManager.put("Table.background", bg);
    for (String key : TEXT_UI_KEYS)
      UIManager.put(key, text);
    UIManager.put("Label.disabledForeground", disabledText);
    UIManager.put("Button.disabledText", disabledText);
    UIManager.put("TextField.placeholderForeground", placeholder);
    UIManager.put("TextField.caretForeground", text);
    UIManager.put("FormattedTextField.caretForeground", text);
    UIManager.put("PasswordField.caretForeground", text);
    UIManager.put("TextArea.caretForeground", text);
    UIManager.put("TextPane.caretForeground", text);
    UIManager.put("EditorPane.caretForeground", text);
    UIManager.put("Table.selectionForeground", selectedText);
    UIManager.put("ComboBox.selectionForeground", selectedText);
    UIManager.put("List.selectionForeground", selectedText);
    UIManager.put("Menu.selectionForeground", selectedText);
    UIManager.put("MenuItem.selectionForeground", selectedText);
    UIManager.put("CheckBoxMenuItem.selectionForeground", selectedText);
    UIManager.put("RadioButtonMenuItem.selectionForeground", selectedText);
    UIManager.put("ProgressBar.selectionForeground", selectedText);
    UIManager.put("Table.selectionBackground", selected);
    UIManager.put("TableHeader.background", field);
    UIManager.put("ScrollPane.background", bg);
    UIManager.put("ScrollBar.track", bg);
    UIManager.put("ScrollBar.hoverTrackColor", bg);
    UIManager.put("ScrollBar.thumb",
                  dark ? new Color(82, 82, 82) : new Color(180, 180, 180));
    UIManager.put("ScrollBar.hoverThumbColor",
                  dark ? new Color(110, 110, 110) : new Color(145, 145, 145));
    UIManager.put("ScrollBar.trackArc", 999);
    UIManager.put("ScrollBar.thumbArc", 999);
    UIManager.put("ScrollBar.showButtons", false);
    UIManager.put("PopupMenu.background", field);
    UIManager.put("PopupMenu.borderCornerRadius", 14);
    UIManager.put("PopupMenu.roundedBorderWidth", 1f);
    UIManager.put("PopupMenu.dropShadowPainted", true);
    UIManager.put("Popup.borderCornerRadius", 14);
    UIManager.put("Popup.roundedBorderWidth", 1f);
    UIManager.put("Popup.dropShadowPainted", true);
    UIManager.put("Popup.forceHeavyWeight", true);
    UIManager.put("ComboBox.borderCornerRadius", 14);
    UIManager.put("ComboBox.roundedBorderWidth", 1f);
    UIManager.put("MenuItem.selectionArc", 12);
    UIManager.put("MenuItem.margin", new Insets(6, 10, 6, 10));
    UIManager.put("ComboBox.buttonStyle", "button");
    Font font = new javax.swing.plaf.FontUIResource(findFont());
    UIManager.put("defaultFont", font);
    for (String key : FONT_UI_KEYS)
      UIManager.put(key, font);
    UIManager.put("Button.arc", 10);
    UIManager.put("Component.arc", 10);
    UIManager.put("TextComponent.arc", 10);
    UIManager.put("ScrollPane.arc", 14);
    UIManager.put("ScrollBar.width", 8);
    UIManager.put("Table.rowHeight", 40);
    UIManager.put("Component.focusWidth", 1);
    UIManager.put("Button.innerFocusWidth", 0);
  }
  private static Font findFont() {
    Font cached = resolvedUiFont;
    if (cached != null)
      return cached;
    synchronized (TagWorkbenchWindow.class) {
      if (resolvedUiFont != null)
        return resolvedUiFont;
      try (InputStream stream = TagWorkbenchWindow.class
               .getResourceAsStream("/fonts/MiSans-Medium.ttf")) {
        if (stream != null) {
          Font bundled =
              Font.createFont(Font.TRUETYPE_FONT, stream).deriveFont(13f);
          if (fontRenders(bundled)) {
            resolvedUiFont = bundled;
            return bundled;
          }
        }
      } catch (Exception ignored) {
        // Continue with installed fonts when the bundled font cannot load.
      }
      List<String> installed = List.of(
          GraphicsEnvironment.getLocalGraphicsEnvironment()
              .getAvailableFontFamilyNames());
      List<String> candidates = new ArrayList<>();
      for (String name : installed)
        if (name.equalsIgnoreCase("MiSans") || name.startsWith("MiSans "))
          candidates.add(name);
      for (String fallback :
           new String[] {"Microsoft YaHei UI", "Microsoft YaHei",
                         "Noto Sans CJK SC", Font.DIALOG})
        if (Font.DIALOG.equals(fallback) ||
            installed.stream().anyMatch(fallback::equalsIgnoreCase))
          candidates.add(fallback);
      for (String name : new LinkedHashSet<>(candidates)) {
        Font candidate = new Font(name, Font.PLAIN, 13);
        if (fontRenders(candidate)) {
          resolvedUiFont = candidate;
          return candidate;
        }
      }
      resolvedUiFont = new Font(Font.DIALOG, Font.PLAIN, 13);
      return resolvedUiFont;
    }
  }
  private static boolean fontRenders(Font font) {
    final String probe = "音乐标签歌词 Aa09";
    if (font == null || font.canDisplayUpTo(probe) >= 0)
      return false;
    BufferedImage image =
        new BufferedImage(180, 40, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      graphics.setFont(font.deriveFont(18f));
      graphics.setColor(Color.BLACK);
      graphics.drawString(probe, 2, 27);
    } catch (RuntimeException error) {
      return false;
    } finally {
      graphics.dispose();
    }
    int painted = 0;
    for (int y = 0; y < image.getHeight(); y++)
      for (int x = 0; x < image.getWidth(); x++)
        if ((image.getRGB(x, y) >>> 24) != 0 && ++painted >= 24)
          return true;
    return false;
  }
  private static boolean detectDarkMode() {
    try {
      Process p = new ProcessBuilder("reg", "query",
                                     "HKCU\\Software\\Microsoft\\Windows\\Cur" +
                                     "rentVersion\\Themes\\Personalize",
                                     "/v", "AppsUseLightTheme")
                      .redirectErrorStream(true)
                      .start();
      String out = new String(p.getInputStream().readAllBytes());
      return out.contains("0x0");
    } catch (Exception ignored) {
      return false;
    }
  }

  private static final class UnifiedTableHeader extends JTableHeader {
    private final boolean separators;
    private final SongModel songs;
    UnifiedTableHeader(TableColumnModel model, boolean separators,
                       SongModel songs) {
      super(model);
      this.separators = separators;
      this.songs = songs;
      setOpaque(false);
      setPreferredSize(new Dimension(0, 38));
      setDefaultRenderer((table, value, selected, focus, row, column) -> {
        int modelIndex = getColumnModel().getColumn(column).getModelIndex();
        if (songs != null && modelIndex == 0)
          return new HeaderCheck(songs.allChecked(), songs.anyChecked());
        JLabel label = new JLabel(value == null ? "" : value.toString(),
                                  SwingConstants.CENTER);
        label.setOpaque(false);
        label.setFont(
            UIManager.getFont("TableHeader.font").deriveFont(Font.PLAIN));
        label.setForeground(UIManager.getColor("TableHeader.foreground"));
        label.setBorder(new EmptyBorder(0, 7, 0, 7));
        return label;
      });
      addMouseListener(new java.awt.event.MouseAdapter() {
        public void mouseClicked(java.awt.event.MouseEvent e) {
          int view = columnAtPoint(e.getPoint());
          if (songs != null && view >= 0 &&
              getColumnModel().getColumn(view).getModelIndex() == 0) {
            songs.selectAll(!songs.allChecked());
            repaint();
          }
        }
      });
    }
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D)g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                          RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(UIManager.getColor("TableHeader.background"));
      g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
      g2.dispose();
      super.paintComponent(g);
    }
    protected void paintChildren(Graphics g) {
      super.paintChildren(g);
      if (!separators)
        return;
      Graphics2D g2 = (Graphics2D)g.create();
      g2.setColor(dark ? new Color(255, 255, 255, 24) : new Color(0, 0, 0, 18));
      int x = 0;
      for (int i = 0; i < getColumnModel().getColumnCount() - 1; i++) {
        x += getColumnModel().getColumn(i).getWidth();
        g2.drawLine(x, 7, x, getHeight() - 8);
      }
      g2.dispose();
    }
  }
  private static final class HeaderCheck extends JComponent {
    private final boolean all, any;
    HeaderCheck(boolean all, boolean any) {
      this.all = all;
      this.any = any;
      setOpaque(false);
    }
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D)g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                          RenderingHints.VALUE_ANTIALIAS_ON);
      int x = (getWidth() - 16) / 2, y = (getHeight() - 16) / 2;
      g2.setColor(all || any ? ACCENT
                             : UIManager.getColor("Component.borderColor"));
      if (all || any)
        g2.fillRoundRect(x, y, 16, 16, 6, 6);
      else
        g2.drawRoundRect(x, y, 15, 15, 6, 6);
      g2.setColor(Color.WHITE);
      g2.setStroke(
          new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      if (all) {
        g2.drawLine(x + 4, y + 8, x + 7, y + 11);
        g2.drawLine(x + 7, y + 11, x + 12, y + 5);
      } else if (any)
        g2.drawLine(x + 4, y + 8, x + 12, y + 8);
      g2.dispose();
    }
  }
  private static final class CenterCellRenderer
      extends DefaultTableCellRenderer {
    CenterCellRenderer() {
      setHorizontalAlignment(CENTER);
      setOpaque(false);
      setBorder(null);
    }
    public Component getTableCellRendererComponent(JTable t, Object v,
                                                   boolean s, boolean f, int r,
                                                   int c) {
      super.getTableCellRendererComponent(t, v, s, false, r, c);
      setHorizontalAlignment(CENTER);
      setForeground(UIManager.getColor("Table.foreground"));
      setOpaque(false);
      setBorder(null);
      return this;
    }
  }
  private static final class CompactWrapRenderer
      extends DefaultTableCellRenderer {
    CompactWrapRenderer() {
      setHorizontalAlignment(CENTER);
      setVerticalAlignment(CENTER);
      setOpaque(false);
      setBorder(new EmptyBorder(2, 4, 2, 4));
    }
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean selected,
                                                   boolean focused, int row,
                                                   int column) {
      super.getTableCellRendererComponent(table, value, selected, false, row,
                                          column);
      String text = value == null ? "" : value.toString();
      int width = Math.max(24, table.getColumnModel()
                                   .getColumn(column).getWidth() - 10);
      setFont(table.getFont());
      String[] lines = twoLines(text, getFontMetrics(getFont()), width);
      setText(lines.length == 1
          ? lines[0]
          : "<html><div style='text-align:center'>" + html(lines[0]) +
                "<br>" + html(lines[1]) + "</div></html>");
      setForeground(UIManager.getColor("Table.foreground"));
      setOpaque(false);
      return this;
    }
    private static String html(String value) {
      return value.replace("&", "&amp;").replace("<", "&lt;")
          .replace(">", "&gt;").replace("\"", "&quot;");
    }
    private static String[] twoLines(String value, FontMetrics metrics,
                                     int width) {
      if (metrics.stringWidth(value) <= width)
        return new String[] {value};
      int split = fittingEnd(value, metrics, width);
      if (split <= 0)
        return new String[] {ellipsis(value, metrics, width)};
      int space = value.lastIndexOf(' ', split);
      if (space > split / 2)
        split = space;
      String first = value.substring(0, split).stripTrailing(),
             rest = value.substring(split).stripLeading();
      return new String[] {first, ellipsis(rest, metrics, width)};
    }
    private static int fittingEnd(String value, FontMetrics metrics,
                                  int width) {
      int end = 0;
      while (end < value.length() &&
             metrics.stringWidth(value.substring(0, end + 1)) <= width)
        end++;
      return end;
    }
    private static String ellipsis(String value, FontMetrics metrics,
                                   int width) {
      if (metrics.stringWidth(value) <= width)
        return value;
      int end = value.length();
      while (end > 0 &&
             metrics.stringWidth(value.substring(0, end) + "…") > width)
        end--;
      return value.substring(0, end).stripTrailing() + "…";
    }
  }
  private static final class CheckCellRenderer
      extends JComponent implements javax.swing.table.TableCellRenderer {
    private boolean checked;
    public Component getTableCellRendererComponent(JTable t, Object v,
                                                   boolean s, boolean f, int r,
                                                   int c) {
      checked = Boolean.TRUE.equals(v);
      return this;
    }
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D)g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                          RenderingHints.VALUE_ANTIALIAS_ON);
      int x = (getWidth() - 16) / 2, y = (getHeight() - 16) / 2;
      g2.setColor(checked ? ACCENT
                          : UIManager.getColor("Component.borderColor"));
      if (checked)
        g2.fillRoundRect(x, y, 16, 16, 6, 6);
      else
        g2.drawRoundRect(x, y, 15, 15, 6, 6);
      if (checked) {
        g2.setColor(Color.WHITE);
        g2.setStroke(
            new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawLine(x + 4, y + 8, x + 7, y + 11);
        g2.drawLine(x + 7, y + 11, x + 12, y + 5);
      }
      g2.dispose();
    }
  }
  private static final class CompactCheckBox extends JCheckBox {
    CompactCheckBox(String text, boolean selected) {
      super(text, selected);
      setOpaque(false);
      setFocusPainted(false);
      setIcon(new RoundedCheckIcon());
      setIconTextGap(4);
      setFont(findFont().deriveFont(Font.PLAIN, 11.5f));
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }
  }
  private static final class RoundedCheckIcon implements Icon {
    public int getIconWidth() { return 16; }
    public int getIconHeight() { return 16; }
    public void paintIcon(Component component, Graphics graphics, int x,
                          int y) {
      boolean checked =
          component instanceof AbstractButton button && button.isSelected();
      Graphics2D g = (Graphics2D)graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                         RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(checked ? ACCENT
                         : UIManager.getColor("Component.borderColor"));
      if (checked)
        g.fillRoundRect(x, y, 16, 16, 6, 6);
      else
        g.drawRoundRect(x, y, 15, 15, 6, 6);
      if (checked) {
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND,
                                    BasicStroke.JOIN_ROUND));
        g.drawLine(x + 4, y + 8, x + 7, y + 11);
        g.drawLine(x + 7, y + 11, x + 12, y + 5);
      }
      g.dispose();
    }
  }
  private static final class RoundedTable extends JTable {
    RoundedTable(AbstractTableModel model) {
      super(model);
      setOpaque(false);
      ToolTipManager.sharedInstance().registerComponent(this);
    }
    public String getToolTipText(java.awt.event.MouseEvent e) {
      int row = rowAtPoint(e.getPoint()), col = columnAtPoint(e.getPoint());
      if (row < 0 || col < 0 ||
          (col == 0 && getModel() instanceof SongModel))
        return null;
      Object value = getValueAt(row, col);
      return value == null || value.toString().isBlank() ? null
                                                         : value.toString();
    }
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D)g.create();
      g2.setColor(UIManager.getColor("Table.background"));
      g2.fillRect(0, 0, getWidth(), getHeight());
      int row = getSelectedRow();
      if (row >= 0) {
        Rectangle r = getCellRect(row, 0, true);
        r.width = getWidth();
        g2.setColor(UIManager.getColor("Table.selectionBackground"));
        g2.fillRoundRect(r.x + 3, r.y + 2, r.width - 6, r.height - 4, 14, 14);
      }
      g2.dispose();
      super.paintComponent(g);
    }
  }
  private static final class RoundedScrollPane extends JScrollPane {
    RoundedScrollPane(Component view) {
      super(view);
      setOpaque(false);
    }
    public void paint(Graphics g) {
      Graphics2D g2 = (Graphics2D)g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                          RenderingHints.VALUE_ANTIALIAS_ON);
      g2.clip(new java.awt.geom.RoundRectangle2D.Float(0, 0, getWidth(),
                                                       getHeight(), 14, 14));
      g2.setColor(viewport.getView() instanceof JTextComponent
                      ? UIManager.getColor("TextArea.background")
                      : UIManager.getColor("Panel.background"));
      g2.fillRect(0, 0, getWidth(), getHeight());
      super.paint(g2);
      g2.dispose();
    }
  }
  private static final class CoverView extends JComponent {
    private Image image;
    private boolean suppress;
    CoverView() { setOpaque(false); }
    void setCover(byte[] bytes) {
      if (image != null)
        image.flush();
      image = bytes == null ? null : new ImageIcon(bytes).getImage();
      repaint();
    }
    void setSuppress(boolean value) { suppress = value; }
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D)g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                          RenderingHints.VALUE_ANTIALIAS_ON);
      java.awt.Shape shape = new java.awt.geom.RoundRectangle2D.Float(
          1, 1, getWidth() - 2, getHeight() - 2, 16, 16);
      g2.clip(shape);
      if (suppress) {
        g2.setColor(UIManager.getColor("TextField.background"));
        g2.fill(shape);
      } else if (image == null) {
        g2.setColor(UIManager.getColor("TextField.background"));
        g2.fill(shape);
        g2.setClip(null);
        g2.setColor(UIManager.getColor("Label.disabledForeground"));
        String text = "无封面";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, (getWidth() - fm.stringWidth(text)) / 2,
                      (getHeight() + fm.getAscent()) / 2);
      } else {
        g2.drawImage(image, 1, 1, getWidth() - 2, getHeight() - 2, null);
      }
      g2.setClip(null);
      g2.setColor(dark ? new Color(72, 72, 72) : new Color(205, 208, 214));
      g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 16, 16);
      g2.dispose();
    }
  }
  private static final class AspectCoverView extends JComponent {
    private Image image;
    private int imageWidth, imageHeight;
    private boolean suppress;
    AspectCoverView() { setOpaque(false); }
    void setImage(byte[] bytes) {
      if (image != null)
        image.flush();
      ImageIcon icon = bytes == null ? null : new ImageIcon(bytes);
      image = icon == null ? null : icon.getImage();
      imageWidth = icon == null ? 0 : icon.getIconWidth();
      imageHeight = icon == null ? 0 : icon.getIconHeight();
      repaint();
    }
    void setSuppress(boolean value) { suppress = value; }
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D)g.create();
      g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                          RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                          RenderingHints.VALUE_ANTIALIAS_ON);
      if (suppress) {
        g2.dispose();
        return;
      }
      if (image == null || imageWidth <= 0 || imageHeight <= 0) {
        g2.setColor(UIManager.getColor("TextField.background"));
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
        g2.setColor(UIManager.getColor("Label.disabledForeground"));
        String text = "无封面";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, (getWidth() - fm.stringWidth(text)) / 2,
                      (getHeight() + fm.getAscent()) / 2);
        g2.dispose();
        return;
      }
      double scale = Math.min(1d, Math.min((getWidth() - 4d) / imageWidth,
                                           (getHeight() - 4d) / imageHeight));
      int width = Math.max(1, (int)Math.round(imageWidth * scale)),
          height = Math.max(1, (int)Math.round(imageHeight * scale)),
          x = (getWidth() - width) / 2, y = (getHeight() - height) / 2;
      java.awt.Shape clip =
          new java.awt.geom.RoundRectangle2D.Float(x, y, width, height, 16, 16);
      g2.clip(clip);
      g2.drawImage(image, x, y, width, height, null);
      g2.dispose();
    }
  }
  private static final class SpectrogramView extends JComponent {
    private SpectrogramService.Rendered rendered;
    private String failure = "";
    private int percent;
    private float imageAlpha;
    private Timer fadeTimer;
    SpectrogramView() {
      setOpaque(false);
      setMinimumSize(new Dimension(400, 240));
    }
    void reset() {
      if (fadeTimer != null) {
        fadeTimer.stop();
        fadeTimer = null;
      }
      rendered = null;
      failure = "";
      percent = 0;
      imageAlpha = 0f;
      repaint();
    }
    void setRendered(SpectrogramService.Rendered value, int progress) {
      boolean firstFrame = rendered == null && value != null;
      rendered = value;
      failure = "";
      percent = Math.max(0, Math.min(100, progress));
      if (firstFrame)
        startFade();
      repaint();
    }
    void setFailure(String message) {
      reset();
      failure = message == null ? "频谱生成失败" : message;
      repaint();
    }
    void dispose() {
      reset();
    }
    private void startFade() {
      if (fadeTimer != null)
        fadeTimer.stop();
      imageAlpha = 0f;
      long began = System.nanoTime(), duration = 190_000_000L;
      fadeTimer = new Timer(frameDelay(this), event -> {
        float progress = (float)Math.min(
            1d, (System.nanoTime() - began) / (double)duration);
        imageAlpha = motionCurve(progress);
        repaint();
        if (progress >= 1f) {
          fadeTimer.stop();
          fadeTimer = null;
        }
      });
      fadeTimer.start();
    }
    protected void paintComponent(Graphics graphics) {
      Graphics2D g = (Graphics2D)graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                         RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                         RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                         RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      int left = 58, top = 12, right = 58, bottom = 29;
      int width = Math.max(1, getWidth() - left - right);
      int availableHeight = Math.max(1, getHeight() - top - bottom);
      int overviewHeight =
          Math.max(34, Math.min(48, availableHeight / 7));
      int overviewGap = 7;
      int mainHeight =
          Math.max(1, availableHeight - overviewHeight - overviewGap);
      int overviewTop = top + mainHeight + overviewGap;
      Shape mainPlot = new java.awt.geom.RoundRectangle2D.Float(
          left, top, width, mainHeight, 12, 12);
      Shape overviewPlot = new java.awt.geom.RoundRectangle2D.Float(
          left, overviewTop, width, overviewHeight, 9, 9);
      SpectrogramService.Rendered value = rendered;
      if (value != null) {
        Shape oldClip = g.getClip();
        g.clip(mainPlot);
        g.setComposite(AlphaComposite.SrcOver.derive(
            Math.max(.05f, imageAlpha)));
        g.drawImage(value.image(), left, top, width, mainHeight, null);
        g.setComposite(AlphaComposite.SrcOver);
        if (value.channels() >= 2) {
          int middle = left + width / 2;
          g.setColor(new Color(255, 255, 255, 42));
          g.drawLine(middle, top, middle, top + mainHeight);
        }
        g.setClip(oldClip);
        g.clip(overviewPlot);
        g.setComposite(AlphaComposite.SrcOver.derive(
            Math.max(.05f, imageAlpha)));
        drawOverviewBars(g, value, left, overviewTop, width,
                         overviewHeight);
        g.setComposite(AlphaComposite.SrcOver);
        g.setClip(oldClip);
        drawAxes(g, value, left, top, width, mainHeight, overviewTop,
                 overviewHeight);
      } else {
        g.setFont(findFont().deriveFont(Font.PLAIN, 13f));
        g.setColor(new Color(174, 184, 202));
        String text = failure.isBlank() ? "正在准备频谱图" : failure;
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, left + (width - metrics.stringWidth(text)) / 2,
                     top + (mainHeight + metrics.getAscent()) / 2);
      }
      g.dispose();
    }
    private void drawAxes(Graphics2D g, SpectrogramService.Rendered value,
                          int left, int top, int width, int height,
                          int overviewTop, int overviewHeight) {
      Font font = findFont().deriveFont(Font.PLAIN, 10.5f);
      g.setFont(font);
      g.setColor(new Color(174, 184, 202));
      FontMetrics metrics = g.getFontMetrics();
      int channels = Math.max(1, value.channels());
      int channelWidth = width / channels;
      for (int channel = 0; channel < channels; channel++) {
        int x = left + channel * channelWidth;
        String label = channels == 1
            ? "单声道"
            : channel == 0 ? "左声道 L" : "右声道 R";
        g.drawString(label, x + 9, top + metrics.getAscent() + 5);
      }
      drawFrequencyScale(g, value.sampleRate(), left, top, width, height,
                         metrics, false);
      drawFrequencyScale(g, value.sampleRate(), left, top, width, height,
                         metrics, true);
      g.setColor(new Color(190, 198, 214));
      int baseline = overviewTop + overviewHeight + metrics.getAscent() + 6;
      for (int tick = 0; tick <= 4; tick++) {
        String label =
            durationText(value.durationSeconds() * tick / 4d);
        int x = left + Math.round(width * tick / 4f);
        int textX = x - metrics.stringWidth(label) / 2;
        textX = Math.max(left, Math.min(left + width -
                                       metrics.stringWidth(label), textX));
        g.drawString(label, textX, baseline);
      }
      if (percent < 100) {
        String progress = percent + "%";
        g.drawString(progress,
                     left + width - metrics.stringWidth(progress) - 9,
                     top + metrics.getAscent() + 5);
      }
    }
    private static void drawFrequencyScale(Graphics2D g, int sampleRate,
                                           int left, int top, int width,
                                           int height,
                                           FontMetrics metrics,
                                           boolean rightSide) {
      double maximum = Math.max(1d, sampleRate / 2000d);
      double step = maximum / 4d;
      List<Double> ticks = new ArrayList<>();
      for (double value = 0; value < maximum; value += step)
        ticks.add(value);
      if (ticks.isEmpty() ||
          Math.abs(ticks.get(ticks.size() - 1) - maximum) > .01)
        ticks.add(maximum);
      for (double value : ticks) {
        int y = top + height -
                (int)Math.round(value / maximum * height);
        String label = String.format(java.util.Locale.ROOT, "%.2f", value);
        g.setColor(new Color(174, 184, 202));
        int edge = rightSide ? left + width : left;
        if (rightSide)
          g.drawLine(edge + 1, y, edge + 4, y);
        else
          g.drawLine(edge - 4, y, edge - 1, y);
        int baseline = Math.max(top + metrics.getAscent(),
                                Math.min(top + height, y + metrics.getAscent() /
                                                       2));
        int labelX = rightSide ? edge + 7
                               : edge - metrics.stringWidth(label) - 7;
        g.drawString(label, labelX, baseline);
      }
      int edge = rightSide ? left + width : left;
      int unitX = rightSide ? edge + 7
                            : edge - metrics.stringWidth("kHz") - 7;
      g.drawString("kHz", unitX, Math.max(metrics.getAscent(), top - 2));
    }
    private static void drawOverviewBars(
        Graphics2D g, SpectrogramService.Rendered value, int left, int top,
        int width, int height) {
      float[] envelope = value.overviewEnvelope();
      if (envelope == null || envelope.length == 0)
        return;
      g.setPaint(new LinearGradientPaint(
          0, top, 0, top + height,
          // Squared stop positions produce a square-root color progression
          // along the vertical axis, matching the main spectrum.
          new float[] {0f, .0196f, .1296f, .3844f, .6724f, 1f},
          new Color[] {new Color(255, 92, 24),
                       new Color(255, 218, 0),
                       new Color(31, 222, 76),
                       new Color(0, 190, 215),
                       new Color(42, 72, 204),
                       new Color(91, 27, 156)}));
      g.setStroke(new BasicStroke(1f));
      for (int x = 0; x < width; x++) {
        int source = Math.min(
            envelope.length - 1,
            (int)Math.floor(x * envelope.length / (double)width));
        int barHeight = Math.max(
            1, Math.round(envelope[source] * (height - 2)));
        g.drawLine(left + x, top + height - 1,
                   left + x, top + height - barHeight);
      }
    }
  }
  private static final class SnapshotBodyPanel extends JPanel {
    private Timer timer;
    private BufferedImage from, to;
    private float progress;
    private boolean expanding;
    SnapshotBodyPanel(LayoutManager layout) { super(layout); }
    void animate(boolean expanding, Runnable applyTarget) {
      disposeAnimation();
      if (!isShowing() || getWidth() <= 0 || getHeight() <= 0) {
        applyTarget.run();
        revalidate();
        repaint();
        return;
      }
      from = snapshot();
      applyTarget.run();
      layoutTree(this);
      to = snapshot();
      this.expanding = expanding;
      progress = 0f;
      long began = System.nanoTime(), duration = 220_000_000L;
      timer = new Timer(frameDelay(this), e -> {
        progress =
            (float)Math.min(1d, (System.nanoTime() - began) / (double)duration);
        repaint();
        if (progress >= 1f)
          disposeAnimation();
      });
      timer.start();
    }
    private BufferedImage snapshot() {
      BufferedImage image =
          animationBuffer(this, getWidth(), getHeight());
      Graphics2D g = image.createGraphics();
      super.paint(g);
      g.dispose();
      return image;
    }
    private static void layoutTree(Component component) {
      if (component instanceof Container container) {
        container.doLayout();
        for (Component child : container.getComponents())
          layoutTree(child);
      }
    }
    private static void slice(Graphics2D g, BufferedImage image, int x,
                              int width, int dx) {
      if (width <= 0)
        return;
      g.drawImage(image, x + dx, 0, x + dx + width, image.getHeight(), x, 0,
                  x + width, image.getHeight(), null);
    }
    protected void paintChildren(Graphics g) {
      if (from == null || to == null) {
        super.paintChildren(g);
        return;
      }
      Graphics2D g2 = (Graphics2D)g.create();
      g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                          RenderingHints.VALUE_RENDER_QUALITY);
      g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                          RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      float eased = motionCurve(progress);
      int width = getWidth(), left = Math.round(width * .30f),
          travel = width - left;
      if (expanding) {
        slice(g2, from, 0, left, 0);
        g2.setComposite(AlphaComposite.SrcOver.derive(1 - eased));
        slice(g2, from, left, travel, Math.round(travel * eased));
        Shape old = g2.getClip();
        g2.clipRect(0, 0, left + Math.round(travel * eased), getHeight());
        g2.setComposite(AlphaComposite.SrcOver.derive(eased));
        g2.drawImage(to, 0, 0, null);
        g2.setClip(old);
      } else {
        g2.setComposite(AlphaComposite.SrcOver.derive(1 - eased));
        g2.drawImage(from, 0, 0, null);
        g2.setComposite(AlphaComposite.SrcOver.derive(eased));
        slice(g2, to, 0, left, 0);
        slice(g2, to, left, travel, Math.round(travel * (1 - eased)));
      }
      g2.dispose();
    }
    void disposeAnimation() {
      if (timer != null) {
        timer.stop();
        timer = null;
      }
      if (from != null) {
        from.flush();
        from = null;
      }
      if (to != null) {
        to.flush();
        to = null;
      }
      repaint();
    }
  }
  private static final class AnimatedCardPanel extends JPanel {
    private final CardLayout cards = new CardLayout();
    private Timer timer;
    private BufferedImage from, to;
    private Image movingImage;
    private int movingWidth, movingHeight;
    private Rectangle movingFrom, movingTo;
    private float progress;
    private boolean forward;
    private boolean movingStretch, movingHorizontalSlice, slideAllLeft,
        componentFade;
    AnimatedCardPanel() {
      setLayout(cards);
      setOpaque(false);
    }
    void showInstant(String name) {
      disposeAnimation();
      cards.show(this, name);
      doLayout();
      for (Component component : getComponents())
        if (component.isVisible())
          layoutTree(component);
      revalidate();
      repaint();
    }
    void showFadeAnimated(String name) {
      if (!isShowing() || getWidth() <= 0 || getHeight() <= 0) {
        cards.show(this, name);
        return;
      }
      disposeAnimation();
      from = snapshot();
      cards.show(this, name);
      doLayout();
      for (Component component : getComponents())
        if (component.isVisible())
          layoutTree(component);
      to = snapshot();
      componentFade = true;
      progress = 0f;
      long began = System.nanoTime(), duration = 190_000_000L;
      timer = new Timer(frameDelay(this), event -> {
        progress = (float)Math.min(
            1d, (System.nanoTime() - began) / (double)duration);
        repaint();
        if (progress >= 1f)
          disposeAnimation();
      });
      timer.start();
    }
    void showFocusAnimated(String name, boolean forward, JComponent source,
                           JComponent target, byte[] bytes) {
      if (!isShowing() || getWidth() <= 0 || getHeight() <= 0) {
        cards.show(this, name);
        return;
      }
      disposeAnimation();
      movingFrom = relativeBounds(source);
      setSuppressed(source, true);
      from = snapshot();
      setSuppressed(source, false);
      cards.show(this, name);
      doLayout();
      for (Component component : getComponents())
        if (component.isVisible())
          layoutTree(component);
      movingTo = relativeBounds(target);
      setSuppressed(target, true);
      to = snapshot();
      setSuppressed(target, false);
      ImageIcon icon = bytes == null ? null : new ImageIcon(bytes);
      movingImage = icon == null ? null : icon.getImage();
      movingWidth = icon == null ? 0 : icon.getIconWidth();
      movingHeight = icon == null ? 0 : icon.getIconHeight();
      this.forward = forward;
      progress = 0f;
      long began = System.nanoTime(), duration = 210_000_000L;
      timer = new Timer(frameDelay(this), e -> {
        progress =
            (float)Math.min(1d, (System.nanoTime() - began) / (double)duration);
        repaint();
        if (progress >= 1f)
          disposeAnimation();
      });
      timer.start();
    }
    void showComponentFocusAnimated(String name, boolean forward,
                                    JComponent source, JComponent target) {
      if (!isShowing() || getWidth() <= 0 || getHeight() <= 0) {
        cards.show(this, name);
        return;
      }
      disposeAnimation();
      JComponent movingSource = source;
      movingFrom = relativeBounds(movingSource);
      movingImage = componentImage(movingSource);
      movingWidth = Math.max(1, movingSource.getWidth());
      movingHeight = Math.max(1, movingSource.getHeight());
      JComponent sourceFrame = titledAncestor(movingSource);
      Border sourceBorder = hideBorder(sourceFrame);
      from = snapshot();
      restoreBorder(sourceFrame, sourceBorder);
      erase(from, movingFrom);
      cards.show(this, name);
      doLayout();
      for (Component component : getComponents())
        if (component.isVisible())
          layoutTree(component);
      JComponent movingTarget = target;
      Rectangle targetBounds = relativeBounds(movingTarget);
      movingTo = new Rectangle(targetBounds);
      movingTo.y = movingFrom.y;
      movingTo.height = movingFrom.height;
      int fixedRight = movingFrom.x + movingFrom.width;
      movingTo.width = Math.max(1, fixedRight - movingTo.x);
      JComponent targetFrame = titledAncestor(movingTarget);
      Border targetBorder = hideBorder(targetFrame);
      to = snapshot();
      restoreBorder(targetFrame, targetBorder);
      erase(to, targetBounds);
      this.forward = forward;
      movingStretch = true;
      movingHorizontalSlice = true;
      slideAllLeft = true;
      progress = 0f;
      long began = System.nanoTime(), duration = 240_000_000L;
      timer = new Timer(frameDelay(this), event -> {
        progress = (float)Math.min(
            1d, (System.nanoTime() - began) / (double)duration);
        repaint();
        if (progress >= 1f)
          disposeAnimation();
      });
      timer.start();
    }
    void showComponentFadeAnimated(String name, JComponent source,
                                   JComponent target) {
      if (!isShowing() || getWidth() <= 0 || getHeight() <= 0) {
        cards.show(this, name);
        return;
      }
      disposeAnimation();
      movingFrom = relativeBounds(source);
      movingImage = componentImage(source);
      movingWidth = Math.max(1, source.getWidth());
      movingHeight = Math.max(1, source.getHeight());
      from = snapshot();
      erase(from, movingFrom);
      cards.show(this, name);
      doLayout();
      for (Component component : getComponents())
        if (component.isVisible())
          layoutTree(component);
      movingTo = relativeBounds(target);
      to = snapshot();
      erase(to, movingTo);
      componentFade = true;
      progress = 0f;
      long began = System.nanoTime(), duration = 180_000_000L;
      timer = new Timer(frameDelay(this), event -> {
        progress = (float)Math.min(
            1d, (System.nanoTime() - began) / (double)duration);
        repaint();
        if (progress >= 1f)
          disposeAnimation();
      });
      timer.start();
    }
    private JComponent titledAncestor(Component component) {
      Component cursor = component;
      while (cursor != null && cursor != this) {
        if (cursor instanceof JComponent candidate &&
            containsTitle(candidate.getBorder()))
          return candidate;
        cursor = cursor.getParent();
      }
      return null;
    }
    private static boolean containsTitle(Border border) {
      if (border instanceof TitledBorder)
        return true;
      if (border instanceof CompoundBorder compound)
        return containsTitle(compound.getOutsideBorder()) ||
               containsTitle(compound.getInsideBorder());
      return false;
    }
    private static Border hideBorder(JComponent component) {
      if (component == null)
        return null;
      Border border = component.getBorder();
      Insets insets = border == null ? new Insets(0, 0, 0, 0)
                                     : border.getBorderInsets(component);
      component.setBorder(
          new EmptyBorder(insets.top, insets.left, insets.bottom, insets.right));
      return border;
    }
    private static void restoreBorder(JComponent component, Border border) {
      if (component != null)
        component.setBorder(border);
    }
    private BufferedImage componentImage(JComponent component) {
      BufferedImage image = animationBuffer(
          component, component.getWidth(), component.getHeight());
      Graphics2D g = image.createGraphics();
      component.printAll(g);
      g.dispose();
      return image;
    }
    private void erase(BufferedImage image, Rectangle bounds) {
      if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
        return;
      Graphics2D g = image.createGraphics();
      g.setComposite(AlphaComposite.Src);
      g.setColor(UIManager.getColor("Panel.background"));
      g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 14, 14);
      g.dispose();
    }
    private Rectangle relativeBounds(JComponent component) {
      if (component == null || component.getParent() == null)
        return new Rectangle();
      return SwingUtilities.convertRectangle(component.getParent(),
                                             component.getBounds(), this);
    }
    private static void setSuppressed(Component component, boolean value) {
      if (component instanceof CoverView view)
        view.setSuppress(value);
      if (component instanceof AspectCoverView view)
        view.setSuppress(value);
    }
    private BufferedImage snapshot() {
      BufferedImage image =
          animationBuffer(this, getWidth(), getHeight());
      Graphics2D g = image.createGraphics();
      for (Component component : getComponents())
        if (component.isVisible())
          component.printAll(g);
      g.dispose();
      return image;
    }
    private static void layoutTree(Component component) {
      if (component instanceof Container container) {
        container.doLayout();
        for (Component child : container.getComponents())
          layoutTree(child);
      }
    }
    private static void slice(Graphics2D g, BufferedImage image, int x,
                              int width, int dx, int dy) {
      if (width <= 0)
        return;
      g.drawImage(image, x + dx, dy, x + dx + width, dy + image.getHeight(), x,
                  0, x + width, image.getHeight(), null);
    }
    private void drawMovingCover(Graphics2D g, float eased) {
      if (movingImage == null || movingWidth <= 0 || movingHeight <= 0 ||
          movingFrom == null || movingTo == null)
        return;
      int x = Math.round(movingFrom.x + (movingTo.x - movingFrom.x) * eased),
          y = Math.round(movingFrom.y + (movingTo.y - movingFrom.y) * eased),
          boxWidth = Math.round(movingFrom.width +
                                (movingTo.width - movingFrom.width) * eased),
          boxHeight = Math.round(movingFrom.height +
                                 (movingTo.height - movingFrom.height) * eased);
      double scale = Math.min((double)boxWidth / movingWidth,
                              (double)boxHeight / movingHeight);
      int width = movingStretch ? Math.max(1, boxWidth)
                                : Math.max(1, (int)Math.round(movingWidth * scale)),
          height = movingStretch ? Math.max(1, boxHeight)
                                 : Math.max(1, (int)Math.round(movingHeight * scale)),
          dx = movingStretch ? x : x + (boxWidth - width) / 2,
          dy = movingStretch ? y : y + (boxHeight - height) / 2;
      g.setComposite(AlphaComposite.SrcOver);
      Shape oldClip = g.getClip();
      g.clip(new java.awt.geom.RoundRectangle2D.Float(dx, dy, width, height, 16,
                                                      16));
      if (movingHorizontalSlice) {
        int fixed = Math.min(96, Math.min(movingWidth, width));
        int sourceFlexible = Math.max(1, movingWidth - fixed);
        int targetFlexible = Math.max(1, width - fixed);
        g.drawImage(movingImage, dx, dy, dx + targetFlexible, dy + height, 0, 0,
                    sourceFlexible, movingHeight, null);
        g.drawImage(movingImage, dx + targetFlexible, dy, dx + width,
                    dy + height, sourceFlexible, 0, movingWidth, movingHeight,
                    null);
      } else
        g.drawImage(movingImage, dx, dy, width, height, null);
      g.setClip(oldClip);
    }
    protected void paintChildren(Graphics g) {
      if (from == null || to == null) {
        super.paintChildren(g);
        return;
      }
      Graphics2D g2 = (Graphics2D)g.create();
      g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                          RenderingHints.VALUE_RENDER_QUALITY);
      g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                          RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      float eased = motionCurve(progress);
      int width = getWidth(), height = getHeight(),
          left = Math.round(width * .30f), middle = Math.round(width * .35f),
          rightX = left + middle;
      if (componentFade) {
        g2.setComposite(AlphaComposite.SrcOver.derive(1 - eased));
        g2.drawImage(from, 0, 0, null);
        g2.setComposite(AlphaComposite.SrcOver.derive(eased));
        g2.drawImage(to, 0, 0, null);
      } else if (slideAllLeft && forward) {
        g2.setComposite(AlphaComposite.SrcOver.derive(1 - eased));
        g2.drawImage(from, -Math.round(width * eased), 0, null);
        Shape oldClip = g2.getClip();
        int revealX = Math.round(left * (1 - eased));
        g2.clipRect(revealX, 0, width - revealX, height);
        g2.setComposite(AlphaComposite.SrcOver.derive(eased));
        g2.drawImage(to, 0, 0, null);
        g2.setClip(oldClip);
      } else if (slideAllLeft) {
        Shape oldClip = g2.getClip();
        int revealX = Math.round(left * eased);
        g2.clipRect(revealX, 0, width - revealX, height);
        g2.setComposite(AlphaComposite.SrcOver.derive(1 - eased));
        g2.drawImage(from, 0, 0, null);
        g2.setClip(oldClip);
        g2.setComposite(AlphaComposite.SrcOver.derive(eased));
        g2.drawImage(to, -Math.round(left * (1 - eased)), 0, null);
      } else if (forward) {
        g2.setComposite(AlphaComposite.SrcOver.derive(1 - eased));
        slice(g2, from, 0, left, -Math.round(left * eased), 0);
        slice(g2, from, left, middle, 0, Math.round(height * eased));
        slice(g2, from, rightX, width - rightX,
              Math.round((width - rightX) * eased), 0);
        g2.setComposite(AlphaComposite.SrcOver.derive(eased));
        g2.drawImage(to, 0, 0, null);
      } else {
        g2.setComposite(AlphaComposite.SrcOver.derive(1 - eased));
        g2.drawImage(from, 0, 0, null);
        g2.setComposite(AlphaComposite.SrcOver.derive(eased));
        slice(g2, to, 0, left, -Math.round(left * (1 - eased)), 0);
        slice(g2, to, left, middle, 0, Math.round(height * (1 - eased)));
        slice(g2, to, rightX, width - rightX,
              Math.round((width - rightX) * (1 - eased)), 0);
      }
      if (componentFade && movingImage != null && movingTo != null) {
        g2.setComposite(AlphaComposite.SrcOver);
        int x = Math.round(movingFrom.x +
                           (movingTo.x - movingFrom.x) * eased);
        int y = Math.round(movingFrom.y +
                           (movingTo.y - movingFrom.y) * eased);
        int movingBoxWidth = Math.max(
            1, Math.round(movingFrom.width +
                          (movingTo.width - movingFrom.width) * eased));
        int movingBoxHeight = Math.max(
            1, Math.round(movingFrom.height +
                          (movingTo.height - movingFrom.height) * eased));
        Shape oldClip = g2.getClip();
        g2.clip(new java.awt.geom.RoundRectangle2D.Float(
            x, y, movingBoxWidth, movingBoxHeight, 16, 16));
        g2.drawImage(movingImage, x, y, movingBoxWidth, movingBoxHeight, null);
        g2.setClip(oldClip);
      } else
        drawMovingCover(g2, eased);
      g2.dispose();
    }
    void disposeAnimation() {
      if (timer != null) {
        timer.stop();
        timer = null;
      }
      if (from != null) {
        from.flush();
        from = null;
      }
      if (to != null) {
        to.flush();
        to = null;
      }
      if (movingImage != null)
        movingImage.flush();
      movingImage = null;
      movingWidth = 0;
      movingHeight = 0;
      movingFrom = null;
      movingTo = null;
      movingStretch = false;
      movingHorizontalSlice = false;
      slideAllLeft = false;
      componentFade = false;
      repaint();
    }
  }
  private static final class RatioLayout implements LayoutManager {
    private final int gap;
    RatioLayout(int gap) { this.gap = gap; }
    public void addLayoutComponent(String n, Component c) {}
    public void removeLayoutComponent(Component c) {}
    public Dimension preferredLayoutSize(Container p) {
      return new Dimension(1000, 600);
    }
    public Dimension minimumLayoutSize(Container p) {
      return new Dimension(0, 0);
    }
    public void layoutContainer(Container p) {
      if (p.getComponentCount() < 3)
        return;
      Insets i = p.getInsets();
      int width = p.getWidth() - i.left - i.right - gap * 2,
          height = p.getHeight() - i.top - i.bottom;
      int first = (int)(width * .30), second = (int)(width * .25),
          third = width - first - second;
      p.getComponent(0).setBounds(i.left, i.top, first, height);
      p.getComponent(1).setBounds(i.left + first + gap, i.top, second, height);
      p.getComponent(2).setBounds(i.left + first + gap + second + gap, i.top,
                                  third, height);
    }
  }
  private static final class ClippedSettingsLayout implements LayoutManager {
    private final int gap;
    private final double ratio;
    private final java.util.function.BooleanSupplier logExpanded;
    private int listHeight, settingsY, settingsHeight;
    ClippedSettingsLayout(int gap, double ratio,
                          java.util.function.BooleanSupplier logExpanded) {
      this.gap = gap;
      this.ratio = ratio;
      this.logExpanded = logExpanded;
    }
    public void addLayoutComponent(String name, Component component) {}
    public void removeLayoutComponent(Component component) {}
    public Dimension preferredLayoutSize(Container parent) {
      return new Dimension(400, 500);
    }
    public Dimension minimumLayoutSize(Container parent) {
      return new Dimension(0, 0);
    }
    public void layoutContainer(Container parent) {
      if (parent.getComponentCount() < 2)
        return;
      Insets insets = parent.getInsets();
      int width = Math.max(0, parent.getWidth() - insets.left - insets.right),
          height = Math.max(0, parent.getHeight() - insets.top - insets.bottom);
      if (!logExpanded.getAsBoolean() || listHeight <= 0) {
        int available = Math.max(0, height - gap);
        listHeight = Math.max(0, (int)Math.round(available * ratio));
        settingsY = listHeight + gap;
        settingsHeight = Math.max(0, available - listHeight);
      }
      int visibleListHeight =
          logExpanded.getAsBoolean() ? Math.min(listHeight, height) : listHeight;
      parent.getComponent(0).setBounds(insets.left, insets.top, width,
                                       visibleListHeight);
      parent.getComponent(1).setBounds(insets.left, insets.top + settingsY,
                                       width, settingsHeight);
    }
  }
  private static final class TwoColumnLayout implements LayoutManager {
    private final int gap;
    private double firstRatio;
    TwoColumnLayout(int gap, double firstRatio) {
      this.gap = gap;
      this.firstRatio = firstRatio;
    }
    double ratio() { return firstRatio; }
    void setRatio(double value) {
      firstRatio = Math.max(0, Math.min(1, value));
    }
    public void addLayoutComponent(String n, Component c) {}
    public void removeLayoutComponent(Component c) {}
    public Dimension preferredLayoutSize(Container p) {
      return new Dimension(1000, 600);
    }
    public Dimension minimumLayoutSize(Container p) {
      return new Dimension(0, 0);
    }
    public void layoutContainer(Container p) {
      if (p.getComponentCount() < 2)
        return;
      Insets i = p.getInsets();
      int width = p.getWidth() - i.left - i.right - gap,
          height = p.getHeight() - i.top - i.bottom,
          first = (int)(width * firstRatio);
      p.getComponent(0).setBounds(i.left, i.top, first, height);
      p.getComponent(1).setBounds(i.left + first + gap, i.top, width - first,
                                  height);
    }
  }
  private static final class ViewportWidthPanel
      extends JPanel implements Scrollable {
    ViewportWidthPanel(LayoutManager layout) { super(layout); }
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }
    public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
      return 36;
    }
    public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
      return 180;
    }
    public boolean getScrollableTracksViewportWidth() { return true; }
    public boolean getScrollableTracksViewportHeight() { return false; }
  }
  private static final class CenterListRenderer
      extends DefaultListCellRenderer {
    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value,
                                                  int index, boolean selected,
                                                  boolean focus) {
      JLabel label = (JLabel)super.getListCellRendererComponent(
          list, value, index, selected, focus);
      label.setHorizontalAlignment(CENTER);
      return label;
    }
  }
  private static final class EllipsisComboRenderer
      extends DefaultListCellRenderer {
    private final int textWidth;
    EllipsisComboRenderer(int textWidth) {
      this.textWidth = textWidth;
      setHorizontalAlignment(SwingConstants.CENTER);
    }
    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean selected,
        boolean focused) {
      JLabel label = (JLabel)super.getListCellRendererComponent(
          list, value, index, selected, focused);
      String full = value == null ? "" : value.toString();
      label.setHorizontalAlignment(SwingConstants.CENTER);
      label.setToolTipText(full);
      label.setText(ellipsize(full, label.getFontMetrics(label.getFont()),
                              textWidth));
      return label;
    }
    private static String ellipsize(String value, FontMetrics metrics,
                                    int width) {
      if (metrics.stringWidth(value) <= width)
        return value;
      String suffix = "…";
      int end = value.length();
      while (end > 0 &&
             metrics.stringWidth(value.substring(0, end) + suffix) > width)
        end--;
      return value.substring(0, end) + suffix;
    }
  }

  private static final class RoundedBorder extends AbstractBorder {
    private final Color color;
    private final int arc;
    RoundedBorder(Color c, int a) {
      color = c;
      arc = a;
    }
    public void paintBorder(Component c, Graphics g, int x, int y, int w,
                            int h) {
      Graphics2D g2 = (Graphics2D)g.create();
      g2.setColor(dark ? new Color(72, 72, 72) : color);
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                          RenderingHints.VALUE_ANTIALIAS_ON);
      g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
      g2.dispose();
    }
    public Insets getBorderInsets(Component c) {
      return new Insets(5, 7, 5, 7);
    }
  }
  private static final class SongModel extends AbstractTableModel {
    final List<AudioTagData> items = new ArrayList<>();
    final List<Boolean> checked = new ArrayList<>();
    void add(AudioTagData d) {
      add(d, true);
    }
    void add(AudioTagData d, boolean selected) {
      int r = items.size();
      items.add(d);
      checked.add(selected);
      fireTableRowsInserted(r, r);
    }
    void prioritize(Set<Integer> modelRows) {
      if (modelRows == null || modelRows.isEmpty())
        return;
      List<AudioTagData> prioritizedItems = new ArrayList<>(items.size());
      List<Boolean> prioritizedChecked = new ArrayList<>(checked.size());
      for (int pass = 0; pass < 2; pass++)
        for (int i = 0; i < items.size(); i++)
          if (modelRows.contains(i) == (pass == 0)) {
            prioritizedItems.add(items.get(i));
            prioritizedChecked.add(checked.get(i));
          }
      items.clear();
      items.addAll(prioritizedItems);
      checked.clear();
      checked.addAll(prioritizedChecked);
      fireTableDataChanged();
    }
    void clear() {
      int size = items.size();
      items.clear();
      checked.clear();
      if (size > 0)
        fireTableRowsDeleted(0, size - 1);
    }
    boolean allChecked() {
      return !checked.isEmpty() &&
          checked.stream().allMatch(Boolean::booleanValue);
    }
    boolean anyChecked() {
      return checked.stream().anyMatch(Boolean::booleanValue);
    }
    void selectAll(boolean v) {
      for (int i = 0; i < checked.size(); i++)
        checked.set(i, v);
      fireTableDataChanged();
    }
    List<Integer> checkedRows() {
      List<Integer> r = new ArrayList<>();
      for (int i = 0; i < checked.size(); i++)
        if (checked.get(i))
          r.add(i);
      return r;
    }
    public int getRowCount() { return items.size(); }
    public int getColumnCount() { return 7; }
    public String getColumnName(int c) {
      return new String[] {"",     "标题", "艺术家", "专辑",
                           "时长", "位深", "比特率"}[c];
    }
    public Class<?> getColumnClass(int c) {
      return c == 0 ? Boolean.class : String.class;
    }
    public boolean isCellEditable(int r, int c) { return false; }
    public Object getValueAt(int r, int c) {
      AudioTagData d = items.get(r);
      return switch (c) {
        case 0 -> checked.get(r);
        case 1 -> d.displayName();
        case 2 -> d.artist();
        case 3 -> d.album();
        case 4 -> d.duration();
        case 5 -> d.bitDepth();
        default -> d.bitrate();
      };
    }
    public void setValueAt(Object v, int r, int c) {
      if (c == 0) {
        checked.set(r, (Boolean)v);
        fireTableCellUpdated(r, c);
      }
    }
  }
}
