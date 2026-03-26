package com.dbboys.vo;

public class Sql {
    private String sql_type="";
    private String sqlText="";
    private String sql_remainder="";
    private Boolean sql_end=true;
    private int block_depth=0;
    private String block_name="";
    private boolean plain_block_mode=false;
    public String getSqlstr() {
        return sqlText;
    }

    public void setSqlStr(String sqlText) {
        this.sqlText = sqlText;
    }

    public String getSqlType() {
        return sql_type;
    }

    public void setSqlType(String sql_type) {
        this.sql_type = sql_type;
    }

    public Boolean getSqlEnd() {
        return sql_end;
    }

    public void setSqlEnd(Boolean sql_end) {
        this.sql_end = sql_end;
    }

    public String getSqlRemainder() {
        return sql_remainder;
    }

    public void setSqlRemainder(String sql_remainder) {
        this.sql_remainder = sql_remainder;
    }

    public int getBlockDepth() {
        return block_depth;
    }

    public void setBlockDepth(int block_depth) {
        this.block_depth = block_depth;
    }

    public String getBlockName() {
        return block_name;
    }

    public void setBlockName(String block_name) {
        this.block_name = block_name;
    }

    public boolean getPlainBlockMode() {
        return plain_block_mode;
    }

    public void setPlainBlockMode(boolean plain_block_mode) {
        this.plain_block_mode = plain_block_mode;
    }
}
