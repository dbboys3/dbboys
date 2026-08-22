package com.dbboys.ui.util;

import com.dbboys.infra.db.LocalDbRepository;
import com.dbboys.infra.i18n.I18n;
import com.dbboys.model.SshConnect;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared "use existing SSH connection" dropdown used by the create-connection dialog
 * and the remote install/uninstall wizards: populates a ChoiceBox with the saved SSH
 * connections (index 0 = manual entry), maps row index to connection id, and fills
 * an SSH field group on selection.
 */
public final class SshConnectionPicker {

    /** The SSH form fields filled from a selected connection; nullable entries are skipped. */
    public record Fields(TextField host, TextField port, TextField user, ChoiceBox<String> authType,
                         TextField password, TextField keyPath, TextField keyPassphrase) {
    }

    private final ChoiceBox<String> choiceBox;
    /** Maps dropdown index -> ssh connection id (index 0 = manual entry). */
    private final List<Integer> ids = new ArrayList<>();

    public SshConnectionPicker(ChoiceBox<String> choiceBox) {
        this.choiceBox = choiceBox;
    }

    /** (Re)load the saved SSH connections into the dropdown; selects manual entry. */
    public void refresh() {
        ids.clear();
        choiceBox.getItems().clear();
        choiceBox.getItems().add(I18n.t("ssh.prompt.manual_ssh", "-- Manual --"));
        ids.add(0);
        for (SshConnect sc : LocalDbRepository.getAllSsh()) {
            choiceBox.getItems().add(sc.getName());
            ids.add(sc.getId());
        }
        choiceBox.getSelectionModel().select(0);
    }

    /** Saved connection at a dropdown index, or null for manual entry / invalid index. */
    public SshConnect at(int index) {
        if (index <= 0 || index >= ids.size()) {
            return null;
        }
        int id = ids.get(index);
        for (SshConnect sc : LocalDbRepository.getAllSsh()) {
            if (sc.getId() == id) return sc;
        }
        return null;
    }

    /** Currently selected saved connection, or null when manual entry is selected. */
    public SshConnect selected() {
        return at(choiceBox.getSelectionModel().getSelectedIndex());
    }

    /** Fill the SSH fields when a saved connection gets selected; afterFill runs afterwards
     *  (e.g. to refresh password/key row visibility). */
    public void bindFill(Fields fields, Runnable afterFill) {
        choiceBox.getSelectionModel().selectedIndexProperty().addListener((obs, o, n) -> {
            SshConnect sel = at(n.intValue());
            if (sel != null) {
                fillFields(sel, fields);
                if (afterFill != null) afterFill.run();
            }
        });
    }

    /** Copy a saved connection's SSH settings into the field group. */
    public static void fillFields(SshConnect sel, Fields f) {
        f.host().setText(sel.getHost());
        f.port().setText(sel.getPort());
        if (f.user() != null) f.user().setText(sel.getUsername());
        if (sel.isAuthKey()) {
            if (f.authType() != null) f.authType().getSelectionModel().select(1);
            if (f.keyPath() != null) f.keyPath().setText(sel.getKeyPath());
            if (f.keyPassphrase() != null) f.keyPassphrase().setText(sel.getKeyPassphrase());
            if (f.password() != null) f.password().setText("");
        } else {
            if (f.authType() != null) f.authType().getSelectionModel().select(0);
            if (f.password() != null) f.password().setText(sel.getPassword());
            if (f.keyPath() != null) f.keyPath().setText("");
            if (f.keyPassphrase() != null) f.keyPassphrase().setText("");
        }
    }

    /** File chooser for an SSH private key, defaulting to ~/.ssh. */
    public static void browseKeyFile(Window owner, TextField target) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("ssh.prompt.key_path", "Select SSH Private Key"));
        File homeDir = new File(System.getProperty("user.home"));
        if (homeDir.isDirectory()) {
            File sshDir = new File(homeDir, ".ssh");
            chooser.setInitialDirectory(sshDir.isDirectory() ? sshDir : homeDir);
        }
        File selected = chooser.showOpenDialog(owner);
        if (selected != null) {
            target.setText(selected.getAbsolutePath());
        }
    }
}
