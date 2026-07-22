package com.dbboys.ui.controller;

import com.dbboys.app.AppExecutor;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.SshConnect;
import com.dbboys.ui.dialog.AlertUtil;
import com.dbboys.ui.dialog.CustomWindowFrameUtil;
import com.dbboys.ui.icon.IconFactory;
import com.dbboys.ui.icon.IconPaths;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.sshd.sftp.client.SftpClient;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * SFTP file transfer dialog 鈥?local | remote split, drag-and-drop transfer, bottom transfer table.
 */
public class SftpDialogController {

    private static final Logger log = LogManager.getLogger(SftpDialogController.class);
    private static final String TIME_FMT = "yyyy-MM-dd HH:mm:ss";
    private static final int BUF = 262144;

    // =========================== Models ===========================

    static class FileEntry {
        final String name;
        final long bytes;
        final boolean isDir;
        final boolean isLink;
        final String modified;
        FileEntry(String n, long b, boolean d, boolean l, String m) { name = n; bytes = b; isDir = d; isLink = l; modified = m; }
    }

    /** One row in the transfer table; bound properties so the TableView auto-refreshes. */
    static class XferRow {
        enum Dir { UP, DOWN }
        final Dir dir;
        final String fileName;
        final long totalBytes;

        volatile long transferred;
        volatile boolean cancelled;
        volatile boolean failed;
        volatile String failReason;
        volatile long startMs = System.currentTimeMillis();
        volatile long endMs;
        volatile String lastSpeed = "--";  // preserved after completion

        final StringProperty nameP     = new SimpleStringProperty();
        final StringProperty sizeP     = new SimpleStringProperty();
        final StringProperty progressP = new SimpleStringProperty();
        final StringProperty speedP    = new SimpleStringProperty();
        final StringProperty startP    = new SimpleStringProperty();
        final StringProperty elapsedP  = new SimpleStringProperty();
        final StringProperty etaP      = new SimpleStringProperty();
        final StringProperty endP      = new SimpleStringProperty();
        final StringProperty dirP      = new SimpleStringProperty();
        final ObjectProperty<javafx.scene.control.Button> cancelP = new SimpleObjectProperty<>();

        XferRow(String fileName, long totalBytes, Dir dir) {
            this.fileName = fileName;
            this.totalBytes = totalBytes;
            this.dir = dir;

            nameP.set(fileName);
            sizeP.set("...");
            dirP.set(dir == Dir.UP ? I18n.t("sftp.dir.up", "↑ Upload") : I18n.t("sftp.dir.down", "↓ Download"));
            startP.set(fmtTime(startMs));
            speedP.set("--");
            elapsedP.set("0s");
            etaP.set("--");
            endP.set("");
            progressP.set("0 / ...");

            Button cb = new Button("✖");
            cb.setFocusTraversable(false);
            cb.setStyle("-fx-font-size:10px;-fx-padding:0 3 0 3;");
            cb.setOnAction(e -> cancelled = true);
            cancelP.set(cb);
        }

        /** Called every ~100ms from background thread. */
        void tick(long done) {
            this.transferred = done;
            long now = System.currentTimeMillis();
            long elapsed = now - startMs;
            String eStr = elapsed > 60_000
                ? (elapsed / 60_000) + "m" + ((elapsed % 60_000) / 1000) + "s"
                : (elapsed / 1000) + "s";
            long bps = elapsed > 0 ? done * 1000 / elapsed : 0;
            String sStr;
            if (bps > 1048576)       sStr = String.format("%.1f MB/s", bps / 1048576.0);
            else if (bps > 1024)      sStr = (bps / 1024) + " KB/s";
            else                      sStr = bps + " B/s";
            lastSpeed = sStr;
            final double pct = totalBytes > 0 ? (int)(done * 100 / totalBytes) : 0;
            String pStr = fmt(done) + " / " + (totalBytes > 0 ? fmt(totalBytes) : fmt(done)) + "  (" + (int)pct + "%)";

            Platform.runLater(() -> {
                progressP.set(pStr);
                sizeP.set(totalBytes > 0 ? fmt(totalBytes) : fmt(done));
                speedP.set(sStr);
                elapsedP.set(eStr);
                etaP.set("");
            });
        }

        void done(long actualBytes) {
            endMs = System.currentTimeMillis();
            long el = endMs - startMs;
            String eStr = el > 60_000 ? (el/60_000)+"m"+((el%60_000)/1000)+"s" : (el/1000)+"s";
            Platform.runLater(() -> {
                endP.set(fmtTime(endMs));
                sizeP.set(fmt(actualBytes));
                progressP.set(fmt(actualBytes) + " / " + fmt(actualBytes) + "  (100%)");
                speedP.set(lastSpeed); elapsedP.set(eStr); etaP.set("--");
            });
        }

        void fail(String reason) {
            failed = true; failReason = reason;
            endMs = System.currentTimeMillis();
            Platform.runLater(() -> {
                endP.set(fmtTime(endMs));
                speedP.set(lastSpeed); etaP.set("--");
                progressP.set(I18n.t("sftp.status.fail","Failed"));
                if (reason != null && !reason.isEmpty()) nameP.set(fileName + " — " + reason);
            });
        }

        static String fmt(long b) {
            if (b < 1024) return b + " B";
            if (b < 1048576) return String.format("%.1f KB", b/1024.0);
            if (b < 1073741824) return String.format("%.1f MB", b/1048576.0);
            return String.format("%.1f GB", b/1073741824.0);
        }
        static String fmtTime(long ms) { return new SimpleDateFormat(TIME_FMT).format(new Date(ms)); }
    }

    // =========================== Fields ===========================

    private final SftpClient sftp;
    private final SshConnect ssh;
    private final Stage stage = new Stage();

    private String remotePath = "/";
    private String remoteHome = "/";
    private ObservableList<FileEntry> remoteFiles;
    private TableView<FileEntry> remoteTbl;
    private TextField remotePathField;

    private File localDir = new File(System.getProperty("user.home"), "Desktop");
    private ObservableList<FileEntry> localFiles;
    private TextField localPathField;

    // transfer table
    private final ObservableList<XferRow> xferRows = FXCollections.observableArrayList();
    private TableView<XferRow> xferTable;

    private SftpDialogController(SftpClient sftp, SshConnect ssh) {
        this.sftp = sftp; this.ssh = ssh;
    }

    public static void showDialog(javafx.stage.Window owner, SftpClient sftp, SshConnect ssh) {
        new SftpDialogController(sftp, ssh).buildAndShow(owner);
    }

    // =========================== Build ===========================

    private void buildAndShow(javafx.stage.Window owner) {
        SimpleStringProperty title = new SimpleStringProperty(
                I18n.t("sftp.title","SFTP") + " - " + ssh.getUsername() + "@" + ssh.getHost());

        VBox localPane  = buildFilePane(false);
        VBox remotePane = buildFilePane(true);

        SplitPane fileSplit = new SplitPane(localPane, remotePane);
        fileSplit.setDividerPositions(0.5);

        // --- transfer table ---
        xferTable = new TableView<>(xferRows);
        xferTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        xferTable.setPlaceholder(new Label(""));
        xferTable.setPrefHeight(150);
        xferTable.getStyleClass().add("sftp-table");

        TableColumn<XferRow,String> dirCol = col("sftp.col.dir", 55,  c->c.getValue().dirP);
        TableColumn<XferRow,String> nmCol  = col("sftp.col.name", 160, c->c.getValue().nameP);
        TableColumn<XferRow,String> szCol  = col("sftp.col.size", 70,  c->c.getValue().sizeP);
        TableColumn<XferRow,String> prCol  = col("sftp.col.progress", 170, c->c.getValue().progressP);
        TableColumn<XferRow,String> spCol  = col("sftp.col.speed", 80,  c->c.getValue().speedP);
        TableColumn<XferRow,String> stCol  = col("sftp.col.start_time", 130, c->c.getValue().startP);
        TableColumn<XferRow,String> elCol  = col("sftp.col.elapsed", 65,  c->c.getValue().elapsedP);
        TableColumn<XferRow,String> etCol  = col("sftp.col.eta", 60,  c->c.getValue().etaP);
        TableColumn<XferRow,String> enCol  = col("sftp.col.end_time", 130, c->c.getValue().endP);
        TableColumn<XferRow,Button> caCol = new TableColumn<>(I18n.t("sftp.col.cancel","Cancel"));
        caCol.setCellValueFactory(c -> c.getValue().cancelP);
        caCol.setPrefWidth(35);
        caCol.setReorderable(false);
        caCol.setStyle("-fx-alignment: CENTER;");

        //noinspection unchecked
        xferTable.getColumns().addAll(dirCol, nmCol, szCol, prCol, spCol, stCol, elCol, etCol, enCol, caCol);

        // --- outer vertical split ---
        SplitPane outer = new SplitPane(fileSplit, xferTable);
        outer.setOrientation(javafx.geometry.Orientation.VERTICAL);
        outer.setDividerPositions(0.72);

        // --- window ---
        CustomWindowFrameUtil.createModalPopup(stage, title, outer, 1050, 650, true, owner);
        stage.setOnCloseRequest(e -> close());
        stage.setTitle(title.get());
        stage.show();

        // Move focus away from text fields so they don't appear selected
        localPane.requestFocus();

        loadLocal();
        AppExecutor.runAsync(() -> {
            try { remoteHome = sftp.canonicalPath("."); if (remoteHome == null || remoteHome.isEmpty()) remoteHome = "/"; }
            catch (Exception e) { remoteHome = "/"; }
            Platform.runLater(() -> loadRemote(remoteHome));
        });
    }

    private void close() { try { sftp.close(); } catch (Exception ignored) {} stage.close(); }

    private static <S,T> TableColumn<S,T> col(String i18n, double w,
                                              javafx.util.Callback<TableColumn.CellDataFeatures<S,T>, ObservableValue<T>> cellFn) {
        TableColumn<S,T> c = new TableColumn<>(I18n.t(i18n));
        c.setCellValueFactory(cellFn);
        c.setPrefWidth(w);
        c.setReorderable(false);
        c.setStyle("-fx-font-size:11px;");
        return c;
    }

    private static final class CellIconFactory {
        static void apply(TableCell<FileEntry, FileEntry> cell, FileEntry item, boolean empty) {
            if (empty || item == null) { cell.setGraphic(null); cell.setText(null); return; }
            String iconPath = item.isDir ? IconPaths.CREATE_CONNECT_FOLDER : IconPaths.METADATA_CONNECT_OPEN_FILE_ITEM;
            SVGPath svg = new SVGPath();
            svg.setContent(iconPath);
            svg.setScaleX(0.5); svg.setScaleY(0.5);
            svg.getStyleClass().add("icon-button-default");
            StackPane iconWrap = new StackPane(svg);
            iconWrap.setPrefWidth(20); iconWrap.setMinWidth(20); iconWrap.setMaxWidth(20);
            HBox box = new HBox(5, iconWrap, new Label(item.name));
            box.setAlignment(Pos.CENTER_LEFT);
            cell.setGraphic(box);
            cell.setText(null);
        }
    }

    // =========================== File pane ===========================

    private VBox buildFilePane(boolean remoteSide) {
        HBox toolbar = new HBox(3);
        toolbar.setPadding(new Insets(4, 6, 2, 6));
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button up   = iconBtn(IconPaths.SEARCH_REPLACE_PREVIOUS, 0.5, I18n.t("sftp.btn.up","Up"));
        Button home = iconBtn(IconPaths.INSTANCE_SPACE_FILE_PATH_LABEL, 0.5, I18n.t("sftp.btn.home","Home"));
        Button ref  = iconBtn(IconPaths.METADATA_REFRESH_ITEM, 0.5, I18n.t("sftp.btn.refresh","Refresh"));
        Button mk   = iconBtn(IconPaths.MARKDOWN_NEW_FOLDER_ITEM, 0.5, I18n.t("sftp.btn.newdir","New Dir"));
        Button del  = iconBtn(IconPaths.METADATA_DELETE_ITEM, 0.5, I18n.t("sftp.btn.delete","Delete"));
        Button ren  = iconBtn(IconPaths.METADATA_RENAME_ITEM, 0.5, I18n.t("sftp.btn.rename","Rename"));
        Button browse = iconBtn(IconPaths.TREECELL_CONNECT_FOLDER_OPEN, 0.5, I18n.t("sftp.btn.browse","Browse"));

        TextField pathField = new TextField();
        pathField.setStyle("-fx-font-family:monospace;-fx-font-size:11px;-fx-padding:1 4 1 4;");
        pathField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(pathField, Priority.ALWAYS);

        toolbar.getChildren().addAll(up, home, ref, mk, del, ren, pathField, browse);

        // --- path field handling ---
        pathField.setOnAction(e -> {
            String input = pathField.getText().trim();
            if (input.isEmpty()) return;
            if (remoteSide) {
                AppExecutor.runAsync(() -> {
                    try {
                        SftpClient.CloseableHandle h = sftp.openDir(input);
                        sftp.close(h);
                        Platform.runLater(() -> navRemote(input));
                    } catch (Exception ex) {
                        Platform.runLater(() ->
                            AlertUtil.showAlert(I18n.t("sftp.error.title","Error"),
                                I18n.t("sftp.error.dir_not_exist","Directory does not exist") + ": " + input));
                    }
                });
            } else {
                java.io.File f = new java.io.File(input);
                if (f.isDirectory()) {
                    navLocal(f);
                } else {
                    AlertUtil.showAlert(I18n.t("sftp.error.title","Error"),
                        I18n.t("sftp.error.dir_not_exist","Directory does not exist") + ": " + input);
                }
            }
        });

        browse.setOnAction(e -> {
            if (remoteSide) {
                // Remote browse: show a popup listing remote directories for selection
                TableView<FileEntry> browseTbl = new TableView<>();
                browseTbl.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
                browseTbl.getStyleClass().add("sftp-table");

                // Name column with icons
                TableColumn<FileEntry,FileEntry> bs = new TableColumn<>(I18n.t("sftp.col.name","Name"));
                bs.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue()));
                bs.setPrefWidth(250);
                bs.setReorderable(false);
                bs.setStyle("-fx-font-size:11px;");
                bs.setCellFactory(col -> new TableCell<FileEntry,FileEntry>() {
                    @Override protected void updateItem(FileEntry item, boolean empty) {
                        CellIconFactory.apply(this, item, empty);
                    }
                });
                TableColumn<FileEntry,String> bz = new TableColumn<>(I18n.t("sftp.col.size","Size"));
                bz.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().isDir ? "" : XferRow.fmt(c.getValue().bytes)));
                bz.setPrefWidth(75);
                bz.setReorderable(false);
                bz.setStyle("-fx-font-size:11px;");
                TableColumn<FileEntry,String> bm = new TableColumn<>(I18n.t("sftp.col.modified","Modified"));
                bm.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().modified));
                bm.setPrefWidth(130);
                bm.setReorderable(false);
                bm.setStyle("-fx-font-size:11px;");
                //noinspection unchecked
                browseTbl.getColumns().addAll(bs, bz, bm);
                browseTbl.setPrefHeight(280);
                ObservableList<FileEntry> browseItems = FXCollections.observableArrayList();
                browseTbl.setItems(browseItems);

                TextField browsePath = new TextField(remotePath);
                browsePath.setStyle("-fx-font-family:monospace;-fx-font-size:11px;");
                browsePath.setPrefWidth(350);

                Button upBtn = iconBtn(IconPaths.SEARCH_REPLACE_PREVIOUS, 0.5, I18n.t("sftp.btn.up","Up"));
                upBtn.getStyleClass().add("small");
                Button goBtn = iconBtn(IconPaths.METADATA_REFRESH_ITEM, 0.5, I18n.t("sftp.btn.refresh","Refresh"));
                goBtn.getStyleClass().add("small");

                Runnable loadBrowsePath = () -> {
                    String p = browsePath.getText().trim();
                    if (p.isEmpty()) return;
                    AppExecutor.runAsync(() -> {
                        List<FileEntry> entries = new ArrayList<>();
                        try {
                            SftpClient.CloseableHandle h = sftp.openDir(p);
                            try {
                                for (SftpClient.DirEntry de : sftp.listDir(h)) {
                                    String n = de.getFilename();
                                    if (".".equals(n) || "..".equals(n)) continue;
                                    String modTime = "";
                                    try {
                                        FileTime ft = de.getAttributes().getModifyTime();
                                        if (ft != null) {
                                            modTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ft.toMillis()));
                                        }
                                    } catch (Exception ignored) {}
                                    entries.add(new FileEntry(n, de.getAttributes().getSize(),
                                        de.getAttributes().isDirectory(), de.getAttributes().isSymbolicLink(), modTime));
                                }
                            } finally { sftp.close(h); }
                            entries.sort((a,b) -> a.isDir != b.isDir ? (a.isDir?-1:1) : a.name.compareToIgnoreCase(b.name));
                            Platform.runLater(() -> { browseItems.setAll(entries); browsePath.setText(p); });
                        } catch (Exception ex) {
                            Platform.runLater(() -> AlertUtil.showAlert(I18n.t("sftp.error.title","Error"),
                                I18n.t("sftp.error.dir_not_exist","Directory does not exist") + ": " + p));
                        }
                    });
                };
                upBtn.setOnAction(ev -> {
                    String cur = browsePath.getText().trim();
                    int i = cur.lastIndexOf('/');
                    browsePath.setText(i <= 0 ? "/" : cur.substring(0, i));
                    loadBrowsePath.run();
                });
                goBtn.setOnAction(ev -> loadBrowsePath.run());

                HBox browseTop = new HBox(5, upBtn, browsePath, goBtn);
                browseTop.setPadding(new Insets(4,6,2,6));
                HBox.setHgrow(browsePath, Priority.ALWAYS);
                browsePath.setOnAction(ev -> goBtn.fire());
                browseTbl.setOnMouseClicked(ev -> {
                    // Double-click: if dir, refresh the browse popup listing only (not the main view)
                    if (ev.getClickCount() == 2) {
                        FileEntry sel = browseTbl.getSelectionModel().getSelectedItem();
                        if (sel != null && sel.isDir) {
                            browsePath.setText(
                                ("/".equals(browsePath.getText().trim()) ? "" : browsePath.getText().trim()) + "/" + sel.name);
                            loadBrowsePath.run();
                        }
                    }
                });

                VBox browseBox = new VBox(browseTop, browseTbl);
                VBox.setVgrow(browseTbl, Priority.ALWAYS);

                ButtonType okBtnType = new ButtonType(I18n.t("common.confirm","OK"), ButtonBar.ButtonData.OK_DONE);
                ButtonType cancelBtnType = new ButtonType(I18n.t("common.cancel","Cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
                AlertUtil.ContentDialog browseDialog = AlertUtil.createContentDialog(
                    I18n.t("sftp.btn.browse","Browse Remote"), browseBox, 550, 450, okBtnType, cancelBtnType);
                Button okBrowseBtn = browseDialog.getButton(okBtnType);
                okBrowseBtn.setOnAction(ev -> {
                    String np = browsePath.getText().trim();
                    if (!np.isEmpty()) {
                        navRemote(np);
                        browseDialog.getStage().close();
                    }
                });
                browseDialog.getButton(cancelBtnType).setOnAction(ev -> browseDialog.getStage().close());
                browsePath.requestFocus();
                Platform.runLater(() -> { browsePath.selectEnd(); browsePath.deselect(); });
                Platform.runLater(loadBrowsePath::run);
                browseDialog.showAndWait();
            } else {
                // Local browse: native directory chooser
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle(I18n.t("sftp.btn.browse","Browse"));
                if (localDir.exists()) chooser.setInitialDirectory(localDir);
                java.io.File selected = chooser.showDialog(stage);
                if (selected != null) {
                    navLocal(selected);
                }
            }
        });

        TableView<FileEntry> tbl = new TableView<>();
        tbl.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        tbl.setFocusTraversable(true);
        tbl.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tbl.getStyleClass().add("sftp-table");

        TableColumn<FileEntry,FileEntry> nc = new TableColumn<>(I18n.t("sftp.col.name","Name"));
        nc.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue()));
        nc.setPrefWidth(200);
        nc.setReorderable(false);
        nc.setStyle("-fx-font-size:11px;");
        nc.setCellFactory(col -> new TableCell<FileEntry,FileEntry>() {
            @Override protected void updateItem(FileEntry item, boolean empty) {
                CellIconFactory.apply(this, item, empty);
            }
        });
        TableColumn<FileEntry,String> sc = col("sftp.col.size", 75, c -> new SimpleStringProperty(
                c.getValue().isDir ? "" : XferRow.fmt(c.getValue().bytes)));
        TableColumn<FileEntry,String> mc = col("sftp.col.modified", 130, c -> new SimpleStringProperty(c.getValue().modified));
        //noinspection unchecked
        tbl.getColumns().addAll(nc, sc, mc);

        ObservableList<FileEntry> items = FXCollections.observableArrayList();
        tbl.setItems(items);

        if (remoteSide) { remoteFiles=items; remoteTbl=tbl; remotePathField=pathField; }
        else            { localFiles=items;  localPathField=pathField;  }

        // ---- Drag-and-drop ----

        // We wrap the TableView in a StackPane so we can style the border on the pane
        // rather than the table itself (to avoid style clashes with the table's own borders).
        StackPane wrap = new StackPane(tbl);
        wrap.setStyle("-fx-background-color:transparent;");

        // Drag ENTER: show dashed blue border on the wrapper
        wrap.setOnDragEntered(e -> {
            if (e.getGestureSource() != tbl && e.getDragboard().hasFiles()) {
                wrap.setStyle("-fx-background-color:transparent;-fx-border-color:#5b9bd5;-fx-border-width:2px;-fx-border-style:dashed;");
            }
            e.consume();
        });
        // Drag EXIT: clear border
        wrap.setOnDragExited(e -> {
            wrap.setStyle("-fx-background-color:transparent;");
            e.consume();
        });
        // Drag OVER: accept COPY (shows "+" cursor on both sides)
        wrap.setOnDragOver(e -> {
            if (e.getGestureSource() != tbl && e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        // Drag DROPPED: clear border, dispatch
        wrap.setOnDragDropped(e -> {
            wrap.setStyle("-fx-background-color:transparent;");
            if (e.getGestureSource() != tbl && e.getDragboard().hasFiles()) {
                if (remoteSide) doUpload(e.getDragboard().getFiles());
                else           doDownloadToLocal();
            }
            e.setDropCompleted(true);
            e.consume();
        });

        // Drag DETECTED: put files on dragboard
        tbl.setOnDragDetected(e -> {
            ObservableList<FileEntry> sel = tbl.getSelectionModel().getSelectedItems();
            if (sel.isEmpty()) return;
            javafx.scene.input.Dragboard db = tbl.startDragAndDrop(TransferMode.COPY);
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            List<java.io.File> list = new ArrayList<>();
            if (remoteSide) {
                for (FileEntry fe : sel) {
                    if (fe.isDir) continue;
                    try {
                        Path td = Files.createTempDirectory("sftpdrag_");
                        Path tf = td.resolve(fe.name);
                        tf.toFile().createNewFile();
                        tf.toFile().deleteOnExit();
                        td.toFile().deleteOnExit();
                        list.add(tf.toFile());
                    } catch (Exception ex) {
                        log.warn("Drag prep failed: {}", fe.name, ex);
                    }
                }
            } else {
                for (FileEntry fe : sel) {
                    java.io.File f = new java.io.File(localDir, fe.name);
                    if (f.exists()) list.add(f);
                }
            }
            if (!list.isEmpty()) { cc.putFiles(list); db.setContent(cc); }
            e.consume();
        });

        // Double-click
        tbl.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                FileEntry sel = tbl.getSelectionModel().getSelectedItem();
                if (sel != null && sel.isDir) {
                    if (remoteSide) navRemote(joinRemote(sel.name));
                    else           navLocal(new File(localDir, sel.name));
                }
            }
        });

        // Buttons
        up.setOnAction(e ->   { if (remoteSide) navRemote(parentOf(remotePath)); else navLocal(parentLocal()); });
        home.setOnAction(e -> { if (remoteSide) navRemote(remoteHome); else navLocal(new File(System.getProperty("user.home"))); });
        ref.setOnAction(e ->  { if (remoteSide) loadRemote(remotePath); else loadLocal(); });
        mk.setOnAction(e -> newDir(remoteSide));
        del.setOnAction(e -> deleteFiles(remoteSide, tbl));
        ren.setOnAction(e -> renameFile(remoteSide, tbl));

        VBox pane = new VBox(toolbar, wrap);
        VBox.setVgrow(wrap, Priority.ALWAYS);

        String lbl = remoteSide
                ? I18n.t("sftp.pane.remote","Remote") + ": " + ssh.getUsername() + "@" + ssh.getHost()
                : I18n.t("sftp.pane.local","Local");
        Label sl = new Label(lbl);
        sl.setStyle("-fx-font-weight:bold;-fx-font-size:11px;-fx-padding:2 6 2 6;");
        VBox w = new VBox(sl, pane);
        VBox.setVgrow(pane, Priority.ALWAYS);
        return w;
    }

    // =========================== Navigation ===========================

    private void navRemote(String p) { remotePath = p; updateRemotePathField(); loadRemote(p); }
    private void navLocal(File d)   { if (!d.isDirectory()) return; localDir = d; updateLocalPathField(); loadLocal(); }

    private void updateRemotePathField() { if (remotePathField != null) { remotePathField.setText(remotePath); } }
    private void updateLocalPathField()  { if (localPathField != null)  { localPathField.setText(localDir.getAbsolutePath()); } }

    // =========================== List ===========================

    private void loadRemote(String p) {
        remotePath = p;
        updateRemotePathField();
        AppExecutor.runAsync(() -> {
            List<FileEntry> entries = new ArrayList<>();
            try {
                SftpClient.CloseableHandle h = sftp.openDir(p);
                try {
                    for (SftpClient.DirEntry de : sftp.listDir(h)) {
                        String n = de.getFilename();
                        if (".".equals(n) || "..".equals(n)) continue;
                        String modTime = "";
                        try {
                            FileTime ft = de.getAttributes().getModifyTime();
                            if (ft != null) {
                                modTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ft.toMillis()));
                            }
                        } catch (Exception ignored) {}
                        entries.add(new FileEntry(n, de.getAttributes().getSize(),
                                de.getAttributes().isDirectory(), de.getAttributes().isSymbolicLink(), modTime));
                    }
                } finally { sftp.close(h); }
            } catch (Exception e) { log.error("Remote list: {}", p, e); Platform.runLater(() -> remoteFiles.clear()); return; }
            entries.sort((a,b) -> a.isDir != b.isDir ? (a.isDir?-1:1) : a.name.compareToIgnoreCase(b.name));
            Platform.runLater(() -> remoteFiles.setAll(entries));
        });
    }

    private void loadLocal() {
        File d = localDir;
        if (!d.isDirectory()) {
            d = new File(System.getProperty("user.home"), "Desktop");
            if (!d.exists()) d = new File(System.getProperty("user.home"));
            localDir = d;
        }
        updateLocalPathField();
        File[] kids = d.listFiles();
        if (kids == null) { localFiles.clear(); return; }
        List<FileEntry> entries = new ArrayList<>();
        for (File f : kids) {
            String mod = "";
            try { mod = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(f.lastModified())); } catch (Exception ignored) {}
            entries.add(new FileEntry(f.getName(), f.isDirectory()?0:f.length(), f.isDirectory(), false, mod));
        }
        entries.sort((a,b) -> a.isDir != b.isDir ? (a.isDir?-1:1) : a.name.compareToIgnoreCase(b.name));
        localFiles.setAll(entries);
    }

    // =========================== Upload ===========================

    private void doUpload(List<File> files) {
        for (File f : files) {
            if (!f.exists()) continue;
            if (f.isDirectory()) { upDir(f, joinRemote(f.getName())); }
            else {
                // Check if remote file exists before uploading
                final String dest = joinRemote(f.getName());
                final File srcFile = f;
                AppExecutor.runAsync(() -> {
                    boolean exists = false;
                    try {
                        SftpClient.CloseableHandle h = sftp.open(dest, EnumSet.of(SftpClient.OpenMode.Read));
                        sftp.close(h);
                        exists = true;
                    } catch (Exception ignored) {}
                    final boolean fileExists = exists;
                    Platform.runLater(() -> {
                        if (fileExists) {
                            if (!AlertUtil.showConfirm(I18n.t("sftp.title.confirm_overwrite","Confirm Overwrite"),
                                    I18n.t("sftp.prompt.confirm_overwrite","File already exists. Overwrite?") + "\n" + srcFile.getName())) {
                                return;
                            }
                        }
                        XferRow r = new XferRow(srcFile.getName(), srcFile.length(), XferRow.Dir.UP);
                        addRow(r);
                        AppExecutor.runAsync(() -> upFile(srcFile, dest, r));
                    });
                });
            }
        }
        AppExecutor.runAsync(() -> { try { Thread.sleep(500); } catch (InterruptedException ignored) {} Platform.runLater(() -> loadRemote(remotePath)); });
    }

    private void upFile(File src, String dest, XferRow r) {
        try (InputStream is = new BufferedInputStream(new FileInputStream(src))) {
            SftpClient.CloseableHandle h = sftp.open(dest, EnumSet.of(SftpClient.OpenMode.Create, SftpClient.OpenMode.Write, SftpClient.OpenMode.Truncate));
            try {
                byte[] buf = new byte[BUF]; int len; long total = 0, last = 0;
                while ((len = is.read(buf)) != -1) {
                    if (r.cancelled) { r.fail("Cancelled"); return; }
                    sftp.write(h, total, buf, 0, len);
                    total += len;
                    if (System.currentTimeMillis() - last > 100) { r.tick(total); last = System.currentTimeMillis(); }
                }
                r.tick(total);
                r.done(total);
            } finally { sftp.close(h); }
        } catch (Exception ex) { log.error("Upload: {}", src.getName(), ex); r.fail(ex.getMessage()); }
    }

    private void upDir(File d, String rp) {
        try { sftp.mkdir(rp); } catch (Exception ignored) {}
        File[] kids = d.listFiles(); if (kids == null) return;
        for (File c : kids) {
            if (c.isDirectory()) { upDir(c, rp + "/" + c.getName()); }
            else { XferRow r = new XferRow(c.getName(), c.length(), XferRow.Dir.UP); addRow(r); AppExecutor.runAsync(() -> upFile(c, rp + "/" + c.getName(), r)); }
        }
    }

    // =========================== Download ===========================

    private void doDownloadToLocal() {
        ObservableList<FileEntry> sel = remoteTbl.getSelectionModel().getSelectedItems();
        if (sel.isEmpty()) return;
        // Check for local conflicts
        ObservableList<FileEntry> toDownload = FXCollections.observableArrayList();
        StringBuilder conflicts = new StringBuilder();
        for (FileEntry fe : sel) {
            if (!fe.isDir) {
                java.io.File localFile = new java.io.File(localDir, fe.name);
                if (localFile.exists()) {
                    conflicts.append(fe.name).append("\n");
                }
            }
            toDownload.add(fe);
        }
        if (conflicts.length() > 0) {
            if (!AlertUtil.showConfirm(I18n.t("sftp.title.confirm_overwrite","Confirm Overwrite"),
                    I18n.t("sftp.prompt.confirm_overwrite","File already exists. Overwrite?") + "\n" + conflicts)) {
                return;
            }
        }
        dnFiles(toDownload, localDir);
        AppExecutor.runAsync(() -> { try { Thread.sleep(500); } catch (InterruptedException ignored) {} Platform.runLater(this::loadLocal); });
    }

    private void dnFiles(ObservableList<FileEntry> entries, File dest) {
        for (FileEntry fe : entries) {
            if (fe.isDir) { dnDir(joinRemote(fe.name), new File(dest, fe.name)); }
            else { XferRow r = new XferRow(fe.name, fe.bytes, XferRow.Dir.DOWN); addRow(r); AppExecutor.runAsync(() -> dnFile(joinRemote(fe.name), new File(dest, fe.name), r)); }
        }
    }

    private void dnFile(String rp, File lf, XferRow r) {
        lf.getParentFile().mkdirs();
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(lf))) {
            SftpClient.CloseableHandle h = sftp.open(rp, EnumSet.of(SftpClient.OpenMode.Read));
            try {
                long offset = 0, last = 0;
                byte[] buf = new byte[BUF];
                while (true) {
                    if (r.cancelled) { r.fail("Cancelled"); return; }
                    int rd = sftp.read(h, offset, buf, 0, buf.length);
                    if (rd < 0) break;
                    os.write(buf, 0, rd);
                    offset += rd;
                    if (System.currentTimeMillis() - last > 100) { r.tick(offset); last = System.currentTimeMillis(); }
                }
                r.tick(offset);
                r.done(offset);
            } finally { sftp.close(h); }
        } catch (Exception ex) { log.error("Download: {}", rp, ex); r.fail(ex.getMessage()); }
    }

    private void dnDir(String rp, File lp) {
        lp.mkdirs();
        try {
            SftpClient.CloseableHandle h = sftp.openDir(rp);
            try {
                for (SftpClient.DirEntry de : sftp.listDir(h)) {
                    String n = de.getFilename();
                    if (".".equals(n) || "..".equals(n)) continue;
                    if (de.getAttributes().isDirectory()) { dnDir(rp + "/" + n, new File(lp, n)); }
                    else { XferRow r = new XferRow(n, de.getAttributes().getSize(), XferRow.Dir.DOWN); addRow(r); dnFile(rp + "/" + n, new File(lp, n), r); }
                }
            } finally { sftp.close(h); }
        } catch (Exception ex) { log.error("dnDir: {}", rp, ex); }
    }

    // =========================== Transfer table ===========================

    private void addRow(XferRow r) {
        Platform.runLater(() -> {
            xferRows.add(0, r); // newest at top
            if (xferRows.size() > 100) xferRows.remove(99, xferRows.size());
        });
    }

    // =========================== Actions ===========================

    private void newDir(boolean remoteSide) {
        HBox hbox = new HBox();
        hbox.setAlignment(Pos.CENTER_LEFT);
        hbox.getChildren().add(new Label(I18n.t("sftp.prompt.newdir","Enter directory name:") + "  "));

        TextField dirField = new TextField();
        dirField.setPrefWidth(200);
        hbox.getChildren().add(dirField);

        ButtonType okBtn = new ButtonType(I18n.t("common.confirm","OK"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType(I18n.t("common.cancel","Cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
            I18n.t("sftp.title.newdir","New Directory"), hbox, 430, 180, okBtn, cancelBtn);
        Button okButton = dialog.getButton(okBtn);
        okButton.setDisable(true);
        dirField.textProperty().addListener((obs, oldVal, newVal) -> okButton.setDisable(newVal.trim().isEmpty()));
        dirField.requestFocus();

        ButtonType result = dialog.showAndWait();
        if (result != okBtn) return;
        String n = dirField.getText().trim();
        if (n.isEmpty()) return;

        if (remoteSide) {
            AppExecutor.runAsync(() -> {
                try { sftp.mkdir(joinRemote(n)); }
                catch (Exception ex) {
                    log.error("mkdir",ex);
                    Platform.runLater(() -> AlertUtil.showAlert(I18n.t("sftp.error.title","Error"), ex.getMessage()));
                }
                Platform.runLater(() -> loadRemote(remotePath));
            });
        } else {
            File nd = new File(localDir, n);
            if (!nd.mkdirs()) {
                AlertUtil.showAlert(I18n.t("sftp.error.title","Error"),
                    I18n.t("sftp.status.create_dir_failed","Create directory failed"));
            }
            loadLocal();
        }
    }

    private void deleteFiles(boolean remoteSide, TableView<FileEntry> tbl) {
        ObservableList<FileEntry> sel = tbl.getSelectionModel().getSelectedItems();
        if (sel.isEmpty()) return;
        String ns = sel.stream().map(f -> f.name).reduce((a,b)->a+", "+b).orElse("");
        if (!AlertUtil.showConfirm(I18n.t("sftp.title.confirm_delete","Confirm Delete"),
                I18n.t("sftp.prompt.confirm_delete","Delete the following items?") + "\n" + ns)) {
            return;
        }
        if (remoteSide) {
            AppExecutor.runAsync(() -> {
                for (FileEntry fe : sel) { try { String p = joinRemote(fe.name); if (fe.isDir) sftp.rmdir(p); else sftp.remove(p); } catch (Exception ex) { log.error("del",ex); } }
                Platform.runLater(() -> loadRemote(remotePath));
            });
        } else {
            for (FileEntry fe : sel) { File f = new File(localDir, fe.name); if (fe.isDir) delRec(f); else f.delete(); }
            loadLocal();
        }
    }

    private void renameFile(boolean remoteSide, TableView<FileEntry> tbl) {
        FileEntry sel = tbl.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        HBox hbox = new HBox();
        hbox.setAlignment(Pos.CENTER_LEFT);
        hbox.getChildren().add(new Label(I18n.t("sftp.prompt.rename","New name:") + "  "));

        TextField renameField = new TextField(sel.name);
        renameField.setPrefWidth(200);
        renameField.positionCaret(sel.name.length());
        hbox.getChildren().add(renameField);

        ButtonType buttonTypeOk = new ButtonType(I18n.t("common.confirm","OK"), ButtonBar.ButtonData.OK_DONE);
        ButtonType buttonTypeCancel = new ButtonType(I18n.t("common.cancel","Cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        AlertUtil.ContentDialog dialog = AlertUtil.createContentDialog(
            I18n.t("sftp.title.rename","Rename"), hbox, 430, 180, buttonTypeOk, buttonTypeCancel);
        Button okBtn = dialog.getButton(buttonTypeOk);
        okBtn.setDisable(true);
        renameField.textProperty().addListener((obs, oldVal, newVal) -> {
            String trimmed = newVal.trim();
            okBtn.setDisable(trimmed.isEmpty() || trimmed.equals(sel.name));
        });

        renameField.requestFocus();
        ButtonType result = dialog.showAndWait();
        if (result != buttonTypeOk) return;

        String newName = renameField.getText().trim();
        if (newName.isEmpty() || newName.equals(sel.name)) return;

        if (remoteSide) {
            AppExecutor.runAsync(() -> {
                try { sftp.rename(joinRemote(sel.name), joinRemote(newName)); }
                catch (Exception ex) { log.error("rename",ex); Platform.runLater(() -> AlertUtil.showAlert(I18n.t("sftp.error.title","Error"), ex.getMessage())); }
                Platform.runLater(() -> loadRemote(remotePath));
            });
        } else {
            File target = new File(localDir, newName);
            if (!new File(localDir, sel.name).renameTo(target)) {
                AlertUtil.showAlert(I18n.t("sftp.error.title","Error"), I18n.t("sftp.error.rename_failed","Rename failed"));
            }
            loadLocal();
        }
    }

    // =========================== Helpers ===========================

    private String joinRemote(String n) { return "/".equals(remotePath) ? "/" + n : remotePath + "/" + n; }
    private static String parentOf(String p) { if ("/".equals(p)) return "/"; int i = p.lastIndexOf('/'); return i <= 0 ? "/" : p.substring(0, i); }
    private File parentLocal() { File p = localDir.getParentFile(); return p != null && p.isDirectory() ? p : localDir; }
    private static void delRec(File f) { if (f.isDirectory()) { File[] ks = f.listFiles(); if (ks != null) for (File c : ks) delRec(c); } f.delete(); }

    private static Button iconBtn(String iconPath, double scale, String tip) {
        Button b = new Button();
        b.setFocusTraversable(false);
        b.setGraphic(IconFactory.group(iconPath, scale));
        b.setStyle("-fx-font-size:13px;-fx-padding:1 5 1 5;");
        b.setTooltip(new Tooltip(tip));
        return b;
    }
}