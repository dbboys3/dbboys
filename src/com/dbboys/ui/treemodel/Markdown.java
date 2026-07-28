package com.dbboys.ui.treemodel;

import com.dbboys.model.TreeData;
import java.io.File;

public class Markdown extends TreeData{
    private File file;

    public Markdown(File file) {
        super(file.getName());
        this.file = file;
    }
    public Markdown(String name){
        super(name);
    }

    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file;
    }
}
