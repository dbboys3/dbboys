package com.dbboys.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dbboys.vo.ColumnsInfo;
import com.dbboys.vo.CheckInfo;
import com.dbboys.vo.ColumnsCommInfo;
import com.dbboys.vo.ExtTableDfiles;
import com.dbboys.vo.ExtTableInfo;
import com.dbboys.vo.ForeignKeyInfo;
import com.dbboys.vo.FragmentInfo;
import com.dbboys.vo.Index;
import com.dbboys.vo.PrimaryKeyInfo;
import com.dbboys.vo.Procedure;
import com.dbboys.vo.Sequence;
import com.dbboys.vo.Synonym;
import com.dbboys.vo.Table;
import com.dbboys.vo.TableColumn;
import com.dbboys.vo.Trigger;
import com.dbboys.vo.View;


public class DDLRepository {
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    /**
     * 获取当前的数据库
     * @param connection
     * @return
     * @throws SQLException
     */
    private static String getActiveDbname(Connection connection) throws SQLException {
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;
        String sqlstr = "select dbinfo('dbname') as dbname from dual";
        String currdatabase = "sysmaster";
        try {
            preparedStatement = connection.prepareStatement(sqlstr);
            resultSet = preparedStatement.executeQuery();
            while (resultSet.next()){
                currdatabase = resultSet.getString("dbname").trim();
            }
        } finally {
            if(resultSet != null)  resultSet.close();
            if(preparedStatement != null) preparedStatement.close();
        }
        return currdatabase;
    }

    /**
     * 设置当前数据库名
     * @param connection
     * @param database
     */
    private static void setActiveDbname(Connection connection, String database) throws SQLException {
        PreparedStatement preparedStatement = null;
        String sqlstr = "database " + database;
        try {
            preparedStatement = connection.prepareStatement(sqlstr);
            preparedStatement.executeUpdate();
        } finally {
            if(preparedStatement != null) preparedStatement.close();
        }
    }

    /**
     * 是否需要打印sqlmode？3.5.1之前无此功能。
     * @param version
     * @return
     */
    public static boolean displaySqlMode(int version){
        return (version < 30501)?false:true;
    }

    /**
     * 获取数据库产品号，小版本号，大版本号v8.8/v8.7无实际意义。
     * @param connection
     * @return
     * @throws SQLException
     */
    public static int getDataBaseProductVersionNumber(Connection connection) {
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;
        String sqlstr = "select dbinfo('version_gbase','minor') as version from dual";
        // AEE_3.5.1_3X2_8_25d861
        String pattern = "\\d+\\.\\d+\\.\\d+";
        Pattern r = Pattern.compile(pattern);
        String tmpversion = "TL_3.1.0_1";
        int version = 30100;                    // version 3.2.0

        // version_gbase 在 3.2.x及之后的版本中支持；
        // 3.5.1及之后支持sqlmode写法
        try {
            preparedStatement = connection.prepareStatement(sqlstr);
            resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                tmpversion = trim(resultSet.getString("version"));
            }
        } catch (SQLException e){   // 没有version_gbase, 使用默认的tmpversion
            // 不做任何事
        } finally {
            if(resultSet != null) {
                try {
                    resultSet.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if(preparedStatement != null) {
                try {
                    preparedStatement.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
        Matcher m = r.matcher(tmpversion);
        if (m.find()){
            tmpversion = m.group(0);
            String[] tmpsplit = tmpversion.split("\\.");
            version = Integer.parseInt(tmpsplit[0]) * 10000 + Integer.parseInt(tmpsplit[1]) * 100 + Integer.parseInt(tmpsplit[2]);
        }
        return version;
    }

    /**
     * 获取数据库产品号，返回字符串
     * @param connection
     * @return
     */
    public static String getDataBaseProductVersion(Connection connection) {
        String version = "unkown version";
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;
        String sqlstr = "select dbinfo('version_gbase','full') as version from dual";

        try {
            preparedStatement = connection.prepareStatement(sqlstr);
            resultSet = preparedStatement.executeQuery();
            while (resultSet.next()){
                version = trim(resultSet.getString("version"));
            }
        } catch (SQLException e) {      // 版本低于3.2.x
            sqlstr = "select dbinfo('version','full') as version from dual";
            try {
                preparedStatement = connection.prepareStatement(sqlstr);
                resultSet = preparedStatement.executeQuery();
                while (resultSet.next()){
                    version = trim(resultSet.getString("version"));
                }
            } catch (SQLException e1) {
                // 不做任何事情
            } finally {
                if(resultSet != null) {
                    try {
                        resultSet.close();
                    } catch (SQLException e1) {
                        e1.printStackTrace();
                    }
                }
                if(preparedStatement != null) {
                    try {
                        preparedStatement.close();
                    } catch (SQLException e1) {
                        e1.printStackTrace();
                    }
                }
            }
        } finally {
            if(resultSet != null) {
                try {
                    resultSet.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if(preparedStatement != null) {
                try {
                    preparedStatement.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
        return version;
    }

    /**
     * 删除前后空格
     * @param str
     * @return
     */
    private static String trim(String str){
        if (str == null){
            return null;
        } else {
            return str.trim();
        }
    }

    /**
     * 去除字符串右侧的'\0'，需要考虑null值
     * @param str
     * @return
     */
    private static String rtrimascii0(String str){
        if(str == null){
            return null;
        }
        int i = str.length();
        while (i > 0 && str.charAt(i-1) == '\0'){        // '\0' 表示ascii 0
            i--;
        }
        return str.substring(0,i);
    }

    /**
     * 根据参数判断是否需要加双引号
     * @param str
     * @return
     */
    private static String getName(String str){
        String patternDelimIdent = "^[a-z_][a-z0-9_]*$";
        if(Pattern.matches(patternDelimIdent,str)){
            return str;
        } else {
            return "\"" + str + "\"";
        }
    }

    /**
     * 分片表的分片信息
     * @param connection
     * @param tablename
     * @return
     * @throws SQLException
     */
    private static ArrayList<FragmentInfo> getTableFragmentInfo(Connection connection, String tablename) throws SQLException {
        String sql = """
                SELECT frag.colno, frag.strategy, frag.evalpos, frag.exprtext, frag.flags, frag.dbspace, frag.partition
                FROM sysfragments frag, systables tab
                WHERE frag.tabid = tab.tabid
                AND tab.tabname = ?
                AND frag.fragtype = 'T'
                ORDER BY frag.evalpos
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return new ArrayList<>(runner.query(sql, List.of(tablename), resultSet -> {
            FragmentInfo tableFragmentInfo = new FragmentInfo();
            tableFragmentInfo.setFragName(tablename);
            tableFragmentInfo.setColNo(resultSet.getInt("colno"));
            tableFragmentInfo.setStrategy(resultSet.getString("strategy"));
            tableFragmentInfo.setEvalpos(resultSet.getInt("evalpos"));
            tableFragmentInfo.setExprtext(resultSet.getString("exprtext"));
            tableFragmentInfo.setFlags(resultSet.getInt("flags"));
            tableFragmentInfo.setDbspace(resultSet.getString("dbspace"));
            tableFragmentInfo.setPartition(resultSet.getString("partition"));
            return tableFragmentInfo;
        }));
    }

    /**
     * 依据collength返回datetime/interval的类型的后半部分（eg: year to second)
     * @param collength
     * @return
     */
    private static String getDTColTypeName(int collength){
        String coltypeName = null;
        String[] dtname = {"YEAR","","MONTH","","DAY","","HOUR","","MINUTE","","SECOND","FRACTION(1)","FRACTION(2)","FRACTION(3)","FRACTION(4)","FRACTION(5)"};
        int mylength = 0;
        mylength = (collength % 256) / 16;
        coltypeName = " " + dtname[mylength];
        mylength = collength % 16;
        coltypeName = coltypeName + " TO " + dtname[mylength];
        return coltypeName;
    }

    /**
     * 仅日期长度
     * @param coltype
     * @return
     */
    @Deprecated
    private static int getDTLength(String coltype){
        int mylength = 0;
        if     ("YEAR TO DAY".equals(coltype)) { mylength=10; }
        else if("YEAR TO HOUR".equals(coltype)) { mylength=13; }
        else if("YEAR TO MINUTE".equals(coltype)) { mylength=16; }
        else if("YEAR TO SECOND".equals(coltype)) { mylength=19; }
        else if("YEAR TO FRACTION(1)".equals(coltype)) { mylength=21; }
        else if("YEAR TO FRACTION(2)".equals(coltype)) { mylength=22; }
        else if("YEAR TO FRACTION(3)".equals(coltype)) { mylength=23; }
        else if("YEAR TO FRACTION(4)".equals(coltype)) { mylength=24; }
        else if("YEAR TO FRACTION(5)".equals(coltype)) { mylength=25; }
        else if("HOUR TO HOUR".equals(coltype)) { mylength=2; }
        else if("HOUR TO MINUTE".equals(coltype)) { mylength=5; }
        else if("HOUR TO SECOND".equals(coltype)) { mylength=8; }
        else if("HOUR TO FRACTION(1)".equals(coltype)) { mylength=10; }
        else if("HOUR TO FRACTION(2)".equals(coltype)) { mylength=11; }
        else if("HOUR TO FRACTION(3)".equals(coltype)) { mylength=12; }
        else if("HOUR TO FRACTION(4)".equals(coltype)) { mylength=13; }
        else if("HOUR TO FRACTION(5)".equals(coltype)) { mylength=14; }
        return mylength;
    }

    /**
     * 仅日期长度。 从collength的值计算字符显示长度
     * @param collength
     * @return
     */
    private static int getDTLength(int collength){
        int mylength = 2;
        int first = (collength % 256) / 16;
        int last  = collength % 16;
        // 分别对应 yyyy,-,mm,-,dd,' ',hh24,':',mi,':',ss,'.1',2,3,4,5
        int[] len = {4,5,7,8,10,11,13,14,16,17,19,21,22,23,24,25};
        // 当first为0（year）时，长度即为last的长度。
        if (first == 0){
            mylength = len[last];
        } else {
            mylength = len[last] - len[first-1];
        }
        return mylength;
    }

    /**
     * 原计划获取字段长度。
     * @param coltype
     * @param collength
     * @return
     */
    @Deprecated
    private static int getLength(String coltype, int collength){
        return getLength(coltype,collength,3);
    }

    /**
     * 获取字段长度，按版本区分
     * @param coltype
     * @param collength
     * @param dbver
     * @return
     */
    private static int getLength(String coltype, int collength, int dbver){
        int mycollen = collength;
        if     ("SMALLINT".equals(coltype) || "BOOLEAN".equals(coltype)){ mycollen=5; }
        else if("INTEGER".equals(coltype) || "SERIAL".equals(coltype) || "DATE".equals(coltype)){ mycollen=10; }
        else if("INT8".equals(coltype) || "SERIAL8".equals(coltype) || "BIGINT".equals(coltype) || "BIGSERIAL".equals(coltype)){ mycollen=19; }
        else if("FLOAT".equals(coltype)){ mycollen=17; }
        else if("SMALLFLOAT".equals(coltype)){ mycollen=7; }
        else if("DECIMAL".equals(coltype) || "MONEY".equals(coltype)){ mycollen=collength/256; }
        else if("TEXT".equals(coltype) || "BYTE".equals(coltype) || "BLOB".equals(coltype) || "CLOB".equals(coltype)) { mycollen=2147483647; }
        // collength = (min_space * 256) + max_size （对于2.0及之前），collength = (min_space * 65536) + max_size（3.0及之后）
        else if("VARCHAR".equals(coltype) || "NVARCHAR".equals(coltype) || "VARCHAR2".equals(coltype) || "NVARCHAR2".equals(coltype)){
            if (dbver == 3) {
                if(collength > 0){
                    mycollen = collength%65536;
                } else {
                    mycollen = Long.valueOf((collength + 4294967296L) % 65536).intValue();
                }
            } else {
                if(collength > 0){
                    mycollen=collength%256;
                } else {
                    mycollen = (collength + 65536) % 256;
                }
            }
        }
        else if(coltype.startsWith("DATETIME")){ mycollen=getDTLength(collength); }
        return mycollen;
    }

    /**
     * 返回数据类型名称。除40、41需要使用扩展类型外，其它都可通过coltype,collength计算得到。
     * @param coltype
     * @param collength
     * @param extended_id
     * @param df
     * @param extypename
     * @return
     */
    private static String getColTypeName(int coltype, int collength, int extended_id, int df, String extypename){
        String coltypeName = null;
        int mycoltype = coltype % 256;
        if     (mycoltype == 0){ coltypeName = "CHAR"; }
        else if(mycoltype == 1){ coltypeName = "SMALLINT"; }
        else if(mycoltype == 2){ coltypeName = "INTEGER"; }
        else if(mycoltype == 3){ coltypeName = "FLOAT"; }
        else if(mycoltype == 4){ coltypeName = "SMALLFLOAT"; }
        else if(mycoltype == 5){ coltypeName = "DECIMAL"; }
        else if(mycoltype == 6){ coltypeName = "SERIAL"; }
        else if(mycoltype == 7){ coltypeName = "DATE"; }
        else if(mycoltype == 8){ coltypeName = "MONEY"; }
        else if(mycoltype == 9){ coltypeName = "NULL"; }
        else if(mycoltype == 10){ coltypeName = "DATETIME" + getDTColTypeName(collength); }
        else if(mycoltype == 11){ coltypeName = "BYTE"; }
        else if(mycoltype == 12){ coltypeName = "TEXT"; }
        else if(mycoltype == 13){ coltypeName = "VARCHAR"; }
        else if(mycoltype == 14){ coltypeName = "INTERVAL" + getDTColTypeName(collength); }
        else if(mycoltype == 15){ coltypeName = "NCHAR"; }
        else if(mycoltype == 16){ coltypeName = "NVARCHAR"; }
        else if(mycoltype == 17){ coltypeName = "INT8"; }
        else if(mycoltype == 18){ coltypeName = "SERIAL8"; }
        else if(mycoltype == 19){ coltypeName = "SET(LVARCHAR)"; }
        else if(mycoltype == 20){ coltypeName = "MULTISET(SENDRECEIVE)"; }
        else if(mycoltype == 21){ coltypeName = "LIST"; }
        else if(mycoltype == 22){ coltypeName = "ROW"; }
        else if(mycoltype == 23){ coltypeName = "COLLECTION"; }
        else if(mycoltype == 24){ coltypeName = "ROWREF"; }
        else if(mycoltype == 40 || mycoltype == 41){ coltypeName = extypename.toUpperCase(); }
        else if(mycoltype == 42){ coltypeName = "REFSERIAL8"; }
        else if(mycoltype == 52){ coltypeName = "BIGINT"; }
        else if(mycoltype == 53){ coltypeName = "BIGSERIAL"; }
        else if(mycoltype == 63){ coltypeName = "VARCHAR2"; }
        else if(mycoltype == 64){ coltypeName = "NVARCHAR2"; }
        else if(mycoltype == 65){ coltypeName = "TIMESTAMP WITH TIME ZONE"; }
        return coltypeName;
    }
    
    /**
     * 获取注释标识，1为有syscomms表，10为表syscomms表有seqno字段（用于排序）
     * @param connection
     * @return
     * @throws SQLException
     */
    private static int getCommentFlags(Connection connection) throws SQLException {
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;
        int commflags = 0;
        String sqlstr = """
                select sum(comm) as commflags  
                from ( 
                    select 1 as comm from systables t where t.tabname = 'syscomms' 
                    union all 
                    select 10 as comm from syscolumns c,systables t where c.tabid = t.tabid and t.tabname = 'syscomms' and c.colname = 'segno' 
                );
                """;     
        preparedStatement = connection.prepareStatement(sqlstr);
        resultSet = preparedStatement.executeQuery();
        while (resultSet.next()){
            commflags = resultSet.getInt("commflags");
        }
        if(resultSet != null) resultSet.close();
        if(preparedStatement != null) preparedStatement.close();

        return commflags;
    }

    /**
     * 获取表信息
     * @param connection
     * @param tablename
     * @param delimident
     * @return
     * @throws SQLException
     */
    private static Table getTableInfo(Connection connection, String tablename) throws SQLException {
        Table tableInfo = null;
        int commflags = getCommentFlags(connection);
        int dbVersion = 3; // 默认数据库JDBC版本
        // 考虑syscomments表是否存在，是否存在多行的问题，将tablecomm移出主表查询。
        String sql = """
                select t.tabname,dbinfo('dbname') as tablecatalog, t.owner as tableowner,t.locklevel as locktype, 
                t.fextsize as firstextsize, t.nextsize as nextextsize, t.tabtype as tabletype,
                t.flags as tableflags 
                from systables t 
                where t.tabname = ? 
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        List<Table> tables = runner.query(sql, List.of(tablename), resultSet -> {
            Table rowTableInfo = new Table(tablename);
            rowTableInfo.setTableCatalog(resultSet.getString("tablecatalog"));
            rowTableInfo.setTableOwner(trim(resultSet.getString("tableowner")));
            rowTableInfo.setLockType(trim(resultSet.getString("locktype")));
            rowTableInfo.setFirstExtSize(resultSet.getInt("firstextsize"));
            rowTableInfo.setNextExtSize(resultSet.getInt("nextextsize"));
            rowTableInfo.setTableTypeCode(trim(resultSet.getString("tabletype")));
            rowTableInfo.setFlags(resultSet.getInt("tableflags"));
            return rowTableInfo;
        });
        if (! tables.isEmpty()) {
            tableInfo = tables.get(0);
        } else {
            tableInfo = new Table(tablename);
        }
        tableInfo.setName(tablename);

        // comment， comm
        if(commflags == 11){        // 有segno
            sql = """
            select c.comments as tablecomm 
            from syscomms c, systables t 
            where c.tabid = t.tabid and t.tabname = ? 
            order by c.segno asc;
            """;
        } else if(commflags == 1){  // 无segno
            sql = """
            select c.comments as tablecomm 
            from syscomms c, systables t 
            where c.tabid = t.tabid and t.tabname = ?         
            """;
        }

        if (commflags > 0){
            List<String> commStrings = runner.query(sql, List.of(tablename), resultSet-> {
                String commstr = resultSet.getString("tablecomm");
                return commstr;
            });

            if(! commStrings.isEmpty()){
                tableInfo.setTableComm(commStrings.get(0));
                for(int i=1; i<commStrings.size(); i++) {
                    tableInfo.setTableComm(tableInfo.getTableComm() + commStrings.get(i));
                }
                tableInfo.setTableComm(tableInfo.getTableComm().replace("'","''").trim());
            }
        }

        String jdbcVersion = connection.getMetaData().getDriverVersion();
        dbVersion=Integer.parseInt(jdbcVersion.substring(0,1));
        tableInfo.setDbVersion(dbVersion);
        return tableInfo;
    }

    //added by L3 20260205，用于返回字段列表
    public static ArrayList<ColumnsInfo> getColInfo(Connection connection,String tabname) throws SQLException {
        ArrayList<ColumnsInfo> arrayList = new ArrayList<ColumnsInfo>();
        Table tableInfo=getTableInfo(connection, tabname);
        arrayList=getColInfo(connection,tableInfo);
        return arrayList;
    }

    /**
     * 获取字段信息
     * @param connection
     * @param tablename
     * @param delimident
     * @throws SQLException
     */
    private static ArrayList<ColumnsInfo> getColInfo(Connection connection, Table tableInfo) throws SQLException {
        ArrayList<ColumnsInfo> arrayList = new ArrayList<>();
        ArrayList<ColumnsCommInfo> columnsCommInfos = getColCommInfo(connection,tableInfo);
        // 对于default默认值，C=Current，L=Literal value,N=Null,S=Dbservername or Sitename，T=Today, U=User
        // 对于虚拟表，sysdefaultsexpr可能多行定义
        // 是否存在comments多行的问题，将colcomm移出主表查询。
        String sql = """
                SELECT
                   sc.colno colno
                  ,sc.colname colname
                  ,sc.coltype,sc.collength
                  ,CASE WHEN mod(sc.coltype,256) in (1,2,52,17,6,18,53,5,8) THEN 10 WHEN mod(sc.coltype,256) in (3,4) THEN 2 ELSE 0 END as typep
                  ,CASE WHEN mod(sc.coltype,256) in (5,8) THEN MOD(sc.collength,256) ELSE 0 END as types
                  ,CASE WHEN bitand(sc.coltype,256) = 256 THEN 0 ELSE 1 END as isnullable
                  ,CASE WHEN sc.colattr = 128 THEN 1 ELSE 0 END as ispk
                  ,df.type as coldeftype
                  ,CASE df.type
                         WHEN 'L' THEN get_default_value(sc.coltype, sc.extended_id, sc.collength, df.default::lvarchar(256))::VARCHAR(254)
                         WHEN 'C' THEN 'current year to second'::VARCHAR(254)
                         WHEN 'S' THEN 'dbservername'::VARCHAR(254)
                         WHEN 'U' THEN 'user'::VARCHAR(254)
                         WHEN 'T' THEN 'today'::VARCHAR(254)
                         WHEN 'E' THEN de.default::VARCHAR(254) || ' '
                         ELSE          NULL::VARCHAR(254)
                   END as coldef
                  ,CASE WHEN mod(sc.coltype,256) in (6,18,53) THEN 1 ELSE 0 END as ISAUTOINCREMENT
                  ,sx.name sxname
                FROM systables t
                LEFT JOIN syscolumns sc ON t.tabid = sc.tabid
                LEFT JOIN sysdefaults df ON (t.tabid = df.tabid AND sc.colno = df.colno)
                LEFT JOIN sysdefaultsexpr de ON (t.tabid = de.tabid AND sc.colno = de.colno and de.type='T')
                LEFT JOIN sysxtdtypes sx ON (sx.type = mod(sc.coltype,256) AND sx.extended_id = sc.extended_id)
                WHERE t.tabname = ?
                ORDER BY sc.colno;
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        List<ColumnsInfo> rows = runner.query(sql, List.of(tableInfo.getName()), resultSet -> {
            ColumnsInfo columnsInfo = new ColumnsInfo();
            columnsInfo.setColNo(resultSet.getInt("colno"));
            columnsInfo.setColName(resultSet.getString("colname"));
            String coltypename = getColTypeName(resultSet.getInt("coltype"), resultSet.getInt("collength"), 0, 0, resultSet.getString("sxname"));
            columnsInfo.setColType(coltypename);
            columnsInfo.setColLength(resultSet.getInt("collength"));  
            columnsInfo.setColTypePS(resultSet.getInt("collength"), coltypename, tableInfo.getDbVersion());     
            columnsInfo.setIsNullable((resultSet.getInt("isnullable") == 1));
            columnsInfo.setIsPK((resultSet.getInt("ispk") == 1));
            columnsInfo.setColDefType(resultSet.getString("coldeftype"));
            columnsInfo.setColDef(trim(resultSet.getString("coldef")));
            columnsInfo.setIsAutoincrement((resultSet.getInt("isautoincrement") == 1));
            return columnsInfo;
        });

        for (ColumnsInfo columnsInfo : rows) {
            int size = arrayList.size();
            if (size > 0 && arrayList.get(size - 1).getColNo() == columnsInfo.getColNo()) {
                ColumnsInfo last = arrayList.get(size - 1);
                columnsInfo.setColName(last.getColName());
                columnsInfo.setColType(last.getColType());
                columnsInfo.setColLength(last.getColLength());
                columnsInfo.setTypeP(last.getTypeP());
                columnsInfo.setTypeS(last.getTypeS());
                columnsInfo.setIsNullable(last.isIsNullable());
                columnsInfo.setIsPK(last.isIsPK());
                columnsInfo.setColDefType(last.getColDefType());
                columnsInfo.setColDef(last.getColDef() + trim(columnsInfo.getColDef()));
                columnsInfo.setIsAutoincrement(last.isIsAutoincrement());
                arrayList.set(size - 1, columnsInfo); 
            } else {
                // 增加注释
                for(ColumnsCommInfo commInfo : columnsCommInfos){
                    if (commInfo.getColName().equals(columnsInfo.getColName())){
                        columnsInfo.setColComm(commInfo.getColComm());
                        break;
                    }
                }
                arrayList.add(columnsInfo);
            }
        }
        return arrayList;
    }

    /**
     * 获取字段的注释信息，基于可能存在注释可能超过nvarchar256的情况，适用于指定单表
     * @param connection
     * @param tableInfo
     * @return
     * @throws SQLException
     */
    private static ArrayList<ColumnsCommInfo> getColCommInfo(Connection connection,Table tableInfo) throws SQLException {
        ArrayList<ColumnsCommInfo> arrayList = new ArrayList<>();
        int commflags = getCommentFlags(connection);
        String sql = "";
        int colno;
        String colname;
        String colcomm;
        if (commflags == 11){           // 有segno的版本
            sql = """
            select c.colname,cc.colno,cc.comments,cc.segno 
            from syscolcomms cc, systables t, syscolumns c 
            where cc.tabid = t.tabid  
            and t.tabid = c.tabid 
            and cc.colno = c.colno  
            and t.tabname = ? 
            order by cc.colno asc, cc.segno asc;
            """;
        } else if (commflags == 1){     // 无segno的版本
            sql = """
            select c.colname,cc.colno,cc.comments 
            from syscolcomms cc, systables t, syscolumns c 
            where cc.tabid = t.tabid  
            and t.tabid = c.tabid 
            and cc.colno = c.colno  
            and t.tabname = ? 
            order by cc.colno asc;        
            """;
        } else if (commflags == 0){            // 无注释
            return null;
        }

        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        List<ColumnsCommInfo> rows = runner.query(sql, List.of(tableInfo.getName()), resultSet -> {
            ColumnsCommInfo columnsCommInfo = new ColumnsCommInfo();
            columnsCommInfo.setColName(resultSet.getString("colname"));
            columnsCommInfo.setColNo(resultSet.getInt("colno"));
            columnsCommInfo.setColComm(resultSet.getString("comments"));
            return columnsCommInfo;
        });

        if (! rows.isEmpty()){
            colname = rows.get(0).getColName();
            colno = rows.get(0).getColNo();
            colcomm = rows.get(0).getColComm();
            for (int i=1; i<rows.size(); i++){
                if (colno == rows.get(i).getColNo()){
                    colcomm = colcomm + rows.get(i).getColComm();
                } else {
                    colcomm = trim(colcomm.replace("'", "''"));
                    ColumnsCommInfo columnsCommInfo = new ColumnsCommInfo();
                    columnsCommInfo.setTabName(tableInfo.getName());
                    columnsCommInfo.setColName(colname);
                    columnsCommInfo.setColNo(colno);
                    columnsCommInfo.setColComm(colcomm);
                    arrayList.add(columnsCommInfo);
                    colname = rows.get(i).getColName();
                    colno = rows.get(i).getColNo();
                    colcomm = rows.get(i).getColComm();
                }
            }
            colcomm = trim(colcomm.replace("'", "''"));
            ColumnsCommInfo columnsCommInfo = new ColumnsCommInfo();
            columnsCommInfo.setTabName(tableInfo.getName());
            columnsCommInfo.setColName(colname);
            columnsCommInfo.setColNo(colno);
            columnsCommInfo.setColComm(colcomm);
            arrayList.add(columnsCommInfo);
        }
        return arrayList;
    }

    /**
     * 有精度和标度的数据类型需要处理
     * @param coltype
     * @param collength
     * @return
     */
    private static String getColTypeName(String coltype, int collength){
        String coltypename = coltype;
        // 有最小字段长度，需处理
        if (coltype.startsWith("VARCHAR") || coltype.startsWith("NVARCHAR")) {
            if (collength/65536 > 0){
                coltypename = coltypename + "(" + collength + "," + collength/65536 + ")";
            } else {
                coltypename = coltypename + "(" + collength + ")";
            }
        } else if ("DECIMAL".equals(coltype) || "MONEY".equals(coltype)){
            if (collength%256 == 255){
                coltypename = coltypename + "(" + collength/256 + ")";
            } else {
                coltypename = coltypename + "(" + collength/256 + "," + collength%256 + ")";
            }
        } else if ("LVARCHAR".equals(coltype) || "CHAR".equals(coltype) || "NCHAR".equals(coltype)) {
            coltypename = coltypename + "(" + collength + ")";
        }
        return coltypename;
    }

    /**
     * 获取约束信息
     * @param connection
     * @param tablename
     * @return
     * @throws SQLException
     * 更新 2025-06-16，修复check显示不足问题
     */
    private static ArrayList<CheckInfo> getCheck(Connection connection,String tablename) throws SQLException {
        String sql = """
                SELECT con.constrname, chk.seqno as checkseqno, chk.checktext as checktext
                FROM sysconstraints con, syschecks chk, systables t
                WHERE con.constrid = chk.constrid
                AND con.tabid = t.tabid
                AND t.tabname = ?
                AND con.constrtype = 'C'
                AND chk.TYPE = 'T'
                ORDER BY con.constrname, chk.seqno
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        List<CheckInfo> rows = runner.query(sql, List.of(tablename), resultSet -> {
            CheckInfo checkInfo = new CheckInfo();
            checkInfo.setConstrName(resultSet.getString("constrname"));
            checkInfo.setCheckText(trim(resultSet.getString("checkText")));
            return checkInfo;
        });

        ArrayList<CheckInfo> arrayList = new ArrayList<>();
        for (CheckInfo checkInfo : rows) {
            int size = arrayList.size();
            if (size > 0 && arrayList.get(size - 1).getConstrName().equals(checkInfo.getConstrName())) {
                CheckInfo merged = new CheckInfo();
                merged.setConstrName(arrayList.get(size - 1).getConstrName());
                merged.setCheckText(arrayList.get(size - 1).getCheckText() + checkInfo.getCheckText());
                arrayList.set(size - 1, merged);
            } else {
                arrayList.add(checkInfo);
            }
        }
        return arrayList;
    }

    /**
     * 获取主键索引信息
     * @param connection
     * @param columns
     * @param tablename
     * @param delimident
     * @return
     * @throws SQLException
     */
    private static ArrayList<PrimaryKeyInfo> getPrimaryKey(Connection connection, ArrayList<ColumnsInfo> columns, String tablename) throws SQLException {
        String sql = """
                SELECT con.constrname,con.constrtype,cast(idx.indexkeys as lvarchar) as idxcols
                FROM sysconstraints con, sysindices idx, systables t
                WHERE con.idxname = idx.idxname
                AND con.tabid = t.tabid
                AND t.tabname = ?
                AND con.constrtype in ('P','U')
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return new ArrayList<>(runner.query(sql, List.of(tablename), resultSet -> {
            PrimaryKeyInfo primaryKeyInfo = new PrimaryKeyInfo();
            primaryKeyInfo.setIdxCols(getIdxCols(connection, resultSet.getString("idxcols"), getColNameListByColumnsInfo(columns)));
            primaryKeyInfo.setConstrName(resultSet.getString("constrname"));
            primaryKeyInfo.setConstrType(resultSet.getString("constrtype"));
            return primaryKeyInfo;
        }));
    }

    /**
     * 从ColumnsInfo中获取字段名称列表
     * @param columns
     * @return
     */
    private static ArrayList<String> getColNameListByColumnsInfo(ArrayList<ColumnsInfo> columns){
        ArrayList<String> colNameList = new ArrayList<>();
        for(int i=0;i<columns.size();i++){
            colNameList.add(columns.get(i).getColName());
        }
        return colNameList;
    }

    /**
     * 表的所有索引列表
     * @param connection
     * @param columns
     * @param tablename
     * @return
     * @throws SQLException
     */
    private static ArrayList<Index> getIndexesInfo(Connection connection, ArrayList<ColumnsInfo> columns, String tablename) throws SQLException {
        String sql = """
                SELECT idx.idxname, idx.owner as idxowner, idx.idxtype, idx.clustered as idxcluster, idx.indexkeys::lvarchar as idxcols
                FROM sysindices idx, systables t
                WHERE idx.tabid = t.tabid
                AND t.tabname = ?;
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return new ArrayList<>(runner.query(sql, List.of(tablename), resultSet -> {
            Index indexInfo = new Index(resultSet.getString("idxname"));
            indexInfo.setIndexOwner(trim(resultSet.getString("idxowner")));
            indexInfo.setIndexType(resultSet.getString("idxtype"));
            indexInfo.setIndexCluster(resultSet.getString("idxcluster"));
            indexInfo.setIndexCols(getIdxCols(connection, resultSet.getString("idxcols"), getColNameListByColumnsInfo(columns)));
            return indexInfo;
        }));
    }

    /**
     * 默认值处理。
     * @param coltype
     * @param coldeftype
     * @param coldef
     * @return
     */
    private static String getDefaults(String coltype,String coldeftype, String coldef){
        String strdef = null;
        if ("E".equals(coldeftype)){
            // include function, add '(' and ')'
            if (Pattern.matches(".+\\(.*\\)", coldef)){
                strdef = "(" + coldef + ")";
            } else {
                strdef = coldef;
            }
        } else if ("L".equals(coldeftype)) {
            // INT..., DEC...,BIG...,SMALL
            if ("SMALLINT".equals(coltype) || "INTEGER".equals(coltype) || "SERIAL".equals(coltype) || "SERIAL8".equals(coltype) ||
                    "INT8".equals(coltype) || "BIGSERIAL".equals(coltype) || "BIGINT".equals(coltype) || "FLOAT".equals(coltype) ||
                    "SMALLFLOAT".equals(coltype) || "MONEY".equals(coltype) || "DECIMAL".equals(coltype)) {
                strdef = coldef;

            } else {
                strdef = "\'" + coldef.replace("'", "''") + "\'";
            }
        } else if ("N".equals(coldeftype)){
            strdef = "NULL";
        } else {
            strdef = coldef;
        }
        return strdef;
    }

    /**
     * 获取触发器列表
     * @param connection
     * @param tablename
     * @return
     * @throws SQLException
     */
    private static ArrayList<String> getTriggerList(Connection connection, String tablename) throws SQLException {
        String sql = """
                SELECT trigname
                FROM systriggers tri, systables t
                WHERE tri.tabid = t.tabid
                AND t.tabname = ?;
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return new ArrayList<>(runner.query(sql, List.of(tablename), resultSet -> resultSet.getString("trigname")));
    }

    /**
     * 获取外部表定义，来源于sysexternal
     * @param connection
     * @param tablename
     * @return
     */
    private static ExtTableInfo getExtTableInfo(Connection connection, String tablename) throws SQLException {
        String sql = """
                SELECT t.tabname as tablename, e.fmttype as formattype, e.codeset as codeset, e.recdelim as recorddelimiter,
                e.flddelim as fielddelimiter, e.datefmt as dateformat, e.moneyfmt as moneyformat, e.maxerrors as maxerrors,
                e.rejectfile as rejectfile, e.flags as flags, e.ndfiles as numdfiles
                FROM systables t, sysexternal e
                WHERE t.tabid = e.tabid
                AND t.tabname = ?
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        ExtTableInfo extTableInfo = runner.queryOne(sql, List.of(tablename), resultSet -> {
            ExtTableInfo row = new ExtTableInfo();
            row.setFormatType(resultSet.getString("formattype"));
            row.setCodeSet(resultSet.getString("codeset"));
            row.setRecordDelimiter(resultSet.getString("recorddelimiter"));
            row.setFieldDelimiter(resultSet.getString("fielddelimiter"));
            row.setDateFormat(resultSet.getString("dateformat"));
            row.setMoneyFormat(resultSet.getString("moneyformat"));
            row.setMaxErrors(resultSet.getInt("maxerrors"));
            row.setRejectFile(resultSet.getString("rejectfile"));
            row.setFlags(resultSet.getInt("flags"));
            row.setNumDfiles(resultSet.getInt("numdfiles"));
            return row;
        });
        if (extTableInfo == null) {
            extTableInfo = new ExtTableInfo();
        }
        extTableInfo.setTableName(tablename);
        return extTableInfo;
    }

    /**
     * 返回外部表数据存储文件（多行）
     * @param connection
     * @param tablename
     * @return
     * @throws SQLException
     */
    private static ArrayList<ExtTableDfiles> getExtTableDfiles(Connection connection, String tablename) throws SQLException {
        String sql = """
                SELECT t.tabname as tablename,e.dfentry as datafile, e.blobdir as blobdir, e.clobdir as clobdir
                FROM systables t, sysextdfiles e
                WHERE t.tabid = e.tabid
                AND t.tabname = ?
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return new ArrayList<>(runner.query(sql, List.of(tablename), resultSet -> {
            ExtTableDfiles extTableDfiles = new ExtTableDfiles();
            extTableDfiles.setTableName(resultSet.getString("tablename"));
            extTableDfiles.setDataFile(resultSet.getString("datafile"));
            extTableDfiles.setBlobDir(resultSet.getString("blobdir"));
            extTableDfiles.setClobDir(resultSet.getString("clobdir"));
            return extTableDfiles;
        }));
    }

    /**
     * 获取外键信息表（可能多行）
     * @param connection
     * @param tablename
     * @return
     * @throws SQLException
     */
    private static ArrayList<ForeignKeyInfo> getForeignKeyInfo(Connection connection, String tablename) throws SQLException {
        String sql = """
                SELECT fk_c.constrname,fk_t.owner AS fkowner, fk_t.tabid AS fktabid, fk_t.tabname AS fktabname, 
                cast(fk_i.indexkeys as lvarchar) AS fk_keys, fk_c.idxname as fkidxname, pk_t.owner AS pkowner, 
                pk_t.tabid AS pktabid, pk_t.tabname AS pktabname, cast(pk_i.indexkeys as lvarchar) AS pk_keys,
                pk_i.idxname as pkidxname, obj.state, fk_t.flags
                FROM sysconstraints fk_c, systables fk_t, sysindices fk_i,sysreferences fk_r,
                     sysconstraints pk_c, systables pk_t, sysindices pk_i,sysobjstate obj
                WHERE fk_c.tabid = fk_t.tabid
                AND fk_c.constrname = obj.name
                AND obj.objtype = 'C'
                AND fk_t.tabname = ?
                AND fk_c.constrtype = 'R'
                AND fk_c.idxname = fk_i.idxname
                AND fk_c.constrid = fk_r.constrid
                AND fk_r.PRIMARY = pk_c.constrid
                AND pk_c.tabid = pk_t.tabid
                AND pk_c.idxname = pk_i.idxname;
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return new ArrayList<>(runner.query(sql, List.of(tablename), resultSet -> {
            ForeignKeyInfo foreignKeyInfo = new ForeignKeyInfo();
            foreignKeyInfo.setFkName(resultSet.getString("constrname"));
            foreignKeyInfo.setFkOwner(resultSet.getString("fkowner"));
            foreignKeyInfo.setFkTabid(resultSet.getInt("fktabid"));
            foreignKeyInfo.setFkTabname(resultSet.getString("fktabname"));
            foreignKeyInfo.setFkCols(resultSet.getString("fk_keys"));
            foreignKeyInfo.setFkIdxName(resultSet.getString("fkidxname"));
            foreignKeyInfo.setPkOwner(resultSet.getString("pkowner"));
            foreignKeyInfo.setPkTabid(resultSet.getInt("pktabid"));
            foreignKeyInfo.setPkTabname(resultSet.getString("pktabname"));
            foreignKeyInfo.setPkCols(resultSet.getString("pk_keys"));
            foreignKeyInfo.setPkIdxName(resultSet.getString("pkidxname"));
            foreignKeyInfo.setIsdisabled(("D".equals(resultSet.getString("state")))?true:false);
            foreignKeyInfo.setFlags(resultSet.getInt("flags"));
            return foreignKeyInfo;
        }));
    }

    /**
     * 生成ddl语句，能调用printview, printsequenc,printsynonym等可从systables读取的信息。
     * @param connection
     * @param tablename
     * @return
     * @throws SQLException
     * @throws ClassNotFoundException
     */
    public static String printTable(Connection connection,String tablename) throws SQLException, ClassNotFoundException {
        String patternConstraint = "^[cur]\\d+_\\d+";              // u=unique,r=reference,c=check
        Table tableInfo = getTableInfo(connection,tablename);
        String sqlmode = tableInfo.getTableSqlModeFunc();
        int dbVersion = getDataBaseProductVersionNumber(connection);
        boolean displaySqlMode = displaySqlMode(dbVersion);
        StringBuilder ddl = new StringBuilder();
        
        // 视图，序列、同义词暂时只打印自己的信息
        if ("V".equalsIgnoreCase(tableInfo.getTableTypeCode())){
            return printView(connection,tablename);
        } else if("Q".equalsIgnoreCase(tableInfo.getTableTypeCode())) {
            return printSequence(connection,tablename);
        } else if("P".equalsIgnoreCase(tableInfo.getTableTypeCode()) || "S".equalsIgnoreCase(tableInfo.getTableTypeCode())) {
            return printSynonym(connection, tablename);
        }

        if(displaySqlMode){
            ddl.append("SET ENVIRONMENT SQLMODE '").append(sqlmode).append("';\n");
        }
        ddl.append("CREATE ");
        // global temporary
        ddl.append(tableInfo.getTableGlobalTemporary()).append(" ");
        // external
        if("E".equals(tableInfo.getTableTypeCode())){
            ddl.append("EXTERNAL TABLE ");
        } else {
            ddl.append("TABLE ");
        }
        // OWNER
        if (tableInfo.getTableOwner()==null){
            return "";
        }
        ddl.append(getName(tableInfo.getName())).append(" (\n");

        ArrayList<CheckInfo> checks = getCheck(connection,tablename);
        ArrayList<ColumnsInfo> columns = getColInfo(connection,tableInfo);
        ArrayList<PrimaryKeyInfo> primaryKeys = getPrimaryKey(connection,columns,tablename);
        ArrayList<Index> indexes = getIndexesInfo(connection,columns,tablename);
        ArrayList<FragmentInfo> tableFragments = getTableFragmentInfo(connection,tablename);
        ArrayList<String> triggers = getTriggerList(connection,tablename);
        ArrayList<ForeignKeyInfo> foreignKeys = getForeignKeyInfo(connection,tablename);

        appendColumnsDefinition(ddl, columns);
        appendCheckConstraints(ddl, checks, sqlmode, patternConstraint);
        appendPrimaryConstraints(ddl, primaryKeys, sqlmode, patternConstraint);
        ddl.append("\n) ");

        // E 外部表处理，不考虑maxrows，TODO：没处理外部数据类型对应
        if ("E".equals(tableInfo.getTableTypeCode())){
            appendExternalTableDefinition(connection, ddl, tableInfo);
        } else {        // T 普通表
            appendNormalTableDefinition(
                    connection,
                    ddl,
                    tableInfo,
                    tablename,
                    sqlmode,
                    patternConstraint,
                    columns,
                    indexes,
                    tableFragments,
                    foreignKeys,
                    triggers
            );
        }

        return ddl.toString();
    }

    /**
     * 按顺序处理各个字段及属性
     * @param ddl
     * @param columns
     */
    private static void appendColumnsDefinition(StringBuilder ddl, ArrayList<ColumnsInfo> columns) {
        for (int i = 0; i < columns.size(); i++) {
            ColumnsInfo column = columns.get(i);
            ddl.append("  ").append(getName(column.getColName()));
            ddl.append(" ").append(getColTypeName(
                    column.getColType(),
                    column.getColLength()
            ));
            if (!column.isIsNullable()) {
                ddl.append(" NOT NULL");
            }
            if (column.getColDefType() != null) {
                ddl.append(" DEFAULT ").append(getDefaults(
                        column.getColType(),
                        column.getColDefType(),
                        column.getColDef()
                ));
            }
            if (i < columns.size() - 1) {
                ddl.append(",\n");
            }
        }
    }

    /**
     * 检查约束
     * @param ddl
     * @param checks
     * @param sqlmode
     * @param patternConstraint
     */
    private static void appendCheckConstraints(StringBuilder ddl, ArrayList<CheckInfo> checks, String sqlmode, String patternConstraint) {
        for (CheckInfo check : checks) {
            if ("Oracle".equalsIgnoreCase(sqlmode)) {
                if (!Pattern.matches(patternConstraint, check.getConstrName())) {
                    ddl.append(",\n  CONSTRAINT ").append(getName(check.getConstrName()));
                    ddl.append("  CHECK ").append(check.getCheckText());
                } else {
                    ddl.append(",\n  CHECK ").append(check.getCheckText());
                }
            } else {
                ddl.append(",\n  CHECK ").append(check.getCheckText());
                if (!Pattern.matches(patternConstraint, check.getConstrName())) {
                    ddl.append(" CONSTRAINT ").append(getName(check.getConstrName()));
                }
            }
        }
    }

    /**
     * 主键约束及唯一约束
     * @param ddl
     * @param primaryKeys
     * @param sqlmode
     * @param patternConstraint
     */
    private static void appendPrimaryConstraints(StringBuilder ddl, ArrayList<PrimaryKeyInfo> primaryKeys, String sqlmode, String patternConstraint) {
        for (PrimaryKeyInfo primaryKey : primaryKeys) {
            if ("Oracle".equalsIgnoreCase(sqlmode)) {
                if (!Pattern.matches(patternConstraint, primaryKey.getConstrName())) {
                    ddl.append(",\n  CONSTRAINT ").append(getName(primaryKey.getConstrName()));
                    if ("P".equals(primaryKey.getConstrType())) {
                        ddl.append("  PRIMARY KEY(");
                    } else if ("U".equals(primaryKey.getConstrType())) {
                        ddl.append("  UNIQUE(");
                    }
                    ddl.append(primaryKey.getIdxCols()).append(")");
                } else {
                    if ("P".equals(primaryKey.getConstrType())) {
                        ddl.append(",\n  PRIMARY KEY(");
                    } else if ("U".equals(primaryKey.getConstrType())) {
                        ddl.append(",\n  UNIQUE(");
                    }
                    ddl.append(primaryKey.getIdxCols()).append(")");
                }
            } else {
                if ("P".equals(primaryKey.getConstrType())) {
                    ddl.append(",\n  PRIMARY KEY(");
                } else if ("U".equals(primaryKey.getConstrType())) {
                    ddl.append(",\n  UNIQUE(");
                }
                ddl.append(primaryKey.getIdxCols()).append(")");
                if (!Pattern.matches(patternConstraint, primaryKey.getConstrName())) {
                    ddl.append("  CONSTRAINT ").append(getName(primaryKey.getConstrName()));
                }
            }
        }
    }

    /**
     * 外部表定义
     * @param connection
     * @param ddl
     * @param tableInfo
     * @throws SQLException
     */
    private static void appendExternalTableDefinition(Connection connection, StringBuilder ddl, Table tableInfo) throws SQLException {
        ExtTableInfo extTableInfo = getExtTableInfo(connection, tableInfo.getName());
        ArrayList<ExtTableDfiles> extTableFiles = getExtTableDfiles(connection, tableInfo.getName());
        if (extTableInfo == null) {
            ddl.append(";");
            return;
        }
        ddl.append("\nUSING ( \n  DATAFILES(\n");
        for (int i = 0; i < extTableInfo.getNumDfiles(); i++) {
            ddl.append("    '").append(trim(extTableFiles.get(i).getDataFile()));
            if (extTableFiles.get(i).getBlobDir() != null && !"".equals(trim(extTableFiles.get(i).getBlobDir()))) {
                ddl.append(";BLOBDIR:").append(trim(extTableFiles.get(i).getBlobDir()));
            }
            if (extTableFiles.get(i).getClobDir() != null && !"".equals(trim(extTableFiles.get(i).getClobDir()))) {
                ddl.append(";CLOBDIR:").append(trim(extTableFiles.get(i).getClobDir()));
            }
            if (i == (extTableInfo.getNumDfiles() - 1)) {
                ddl.append("'\n  ),\n");
            } else {
                ddl.append("',\n");
            }
        }

        if ("D".equals(extTableInfo.getFormatType())) {
            ddl.append("  FORMAT 'DELIMITED',\n");
        } else if ("F".equals(extTableInfo.getFormatType())) {
            ddl.append("  FORMAT 'FIXED',\n");
        } else if ("I".equals(extTableInfo.getFormatType())) {
            ddl.append("  FORMAT 'GBASEDBT',\n");
        }
        if (extTableInfo.getFieldDelimiter() != null) {
            ddl.append("  DELIMITER '").append(extTableInfo.getFieldDelimiter()).append("',\n");
        }
        if (extTableInfo.getRecordDelimiter() != null) {
            ddl.append("  RECORDEND '").append(extTableInfo.getRecordDelimiter()).append("',\n");
        }
        if (extTableInfo.getDateFormat() != null) {
            ddl.append("  DBDATE '").append(trim(extTableInfo.getDateFormat())).append("',\n");
        }
        if (extTableInfo.getMoneyFormat() != null) {
            ddl.append("  DBMONEY '").append(trim(extTableInfo.getMoneyFormat())).append("',\n");
        }
        if (extTableInfo.getMaxErrors() != -1) {
            ddl.append("  MAXERRORS ").append(extTableInfo.getMaxErrors()).append(",\n");
        }
        if (extTableInfo.getRejectFile() != null) {
            ddl.append("  REJECTFILE '").append(trim(extTableInfo.getRejectFile())).append("',\n");
        }
        if ((extTableInfo.getFlags() & 4) == 4) {
            ddl.append("  DELUXE,\n");
        } else if ((extTableInfo.getFlags() & 8) == 8) {
            ddl.append("  EXPRESS,\n");
        }
        if ((extTableInfo.getFlags() & 2) == 2) {
            ddl.append("  ESCAPE ON\n);");
        } else {
            ddl.append("  ESCAPE OFF\n);");
        }
    }

    /**
     * 普通表定义
     * @param connection
     * @param ddl
     * @param tableInfo
     * @param tablename
     * @param sqlmode
     * @param patternConstraint
     * @param columns
     * @param indexes
     * @param tableFragments
     * @param foreignKeys
     * @param triggers
     * @throws SQLException
     */
    private static void appendNormalTableDefinition(
            Connection connection,
            StringBuilder ddl,
            Table tableInfo,
            String tablename,
            String sqlmode,
            String patternConstraint,
            ArrayList<ColumnsInfo> columns,
            ArrayList<Index> indexes,
            ArrayList<FragmentInfo> tableFragments,
            ArrayList<ForeignKeyInfo> foreignKeys,
            ArrayList<String> triggers
    ) throws SQLException {
        if (!tableFragments.isEmpty()) {
            ddl.append(buildFragmentString(tableFragments));
        }

        if ("".equals(tableInfo.getTableGlobalTemporary())) {
            ddl.append("\n");
        } else {
            ddl.append("\n").append(tableInfo.getTableGlobalTemporaryLevel()).append(" ");
        }
        ddl.append("EXTENT SIZE ").append(tableInfo.getFirstExtSize()).append(" NEXT SIZE ").append(tableInfo.getNextExtSize());
        ddl.append(" LOCK MODE ").append(tableInfo.getLockTypeFunc()).append(";\n");

        for (Index index : indexes) {
            if (index.getName().startsWith(" ")) {
                continue;
            }
            ddl.append("\nCREATE");
            if ("U".equals(index.getIndexType())) {
                ddl.append(" UNIQUE INDEX");
            } else if ("C".equals(index.getIndexCluster())) {
                ddl.append(" CLUSTER INDEX");
            } else {
                ddl.append(" INDEX");
            }
            // 不再打印表owner及索引owner
            ddl.append(" ").append(getName(index.getName())).append(" ON ");
            ddl.append(getName(tableInfo.getName())).append("(").append(index.getIndexCols()).append(")");
            ddl.append(buildFragmentString(getIndexFragmentInfo(connection, index.getName()))).append(";");
        }
        ddl.append("\n\n");

        ArrayList<String> fkColumns = getColNameListByColumnsInfo(columns);
        for (ForeignKeyInfo foreignKey : foreignKeys) {
            if ("Oracle".equals(foreignKey.getForeignKeyModeFunc())){
                ddl.append("SET ENVIRONMENT SQLMODE 'Oracle';\n");
                ddl.append("ALTER TABLE ").append(getName(foreignKey.getFkTabname()));
                ddl.append(" ADD ");
                if (!Pattern.matches(patternConstraint, foreignKey.getFkName())) {
                    ddl.append("CONSTRAINT ").append(getName(foreignKey.getFkName()));
                }
                ddl.append(" FOREIGN KEY(");
            } else {
                if(displaySqlMode(getDataBaseProductVersionNumber(connection))){
                    ddl.append("SET ENVIRONMENT SQLMODE 'GBase';\n");
                }
                ddl.append("ALTER TABLE ").append(getName(foreignKey.getFkTabname()));
                ddl.append(" ADD CONSTRAINT FOREIGN KEY(");
            }
            ddl.append(getIdxCols(foreignKey.getFkCols(), fkColumns)).append(") ");

            ArrayList<String> pkColumns = getColNameListByTablename(connection, foreignKey.getPkTabname());
            ddl.append("REFERENCES ").append(getName(foreignKey.getPkTabname()));
            ddl.append("(").append(getIdxCols(foreignKey.getPkCols(), pkColumns)).append(")");

            if ("GBase".equals(foreignKey.getForeignKeyModeFunc())) {
                if (!Pattern.matches(patternConstraint, foreignKey.getFkName())) {
                    ddl.append(" CONSTRAINT ").append(getName(foreignKey.getFkName()));
                }
            }
            ddl.append(";\n");

            // Oracle模式也是这么写？？
            if (foreignKey.isIsdisabled()){
                ddl.append("SET CONSTRAINTS ").append(getName(foreignKey.getFkName())).append(" DISABLED;\n");
            }
        }

        if (tableInfo.getTableComm() != null) {
            ddl.append("\nCOMMENT ON TABLE ").append(getName(tablename)).append(" IS '")
                    .append(tableInfo.getTableComm()).append("';");
        }
        for (ColumnsInfo column : columns) {
            if (column.getColComm() != null) {
                ddl.append("\nCOMMENT ON COLUMN ").append(getName(tablename)).append(".")
                        .append(getName(column.getColName())).append(" IS '")
                        .append(column.getColComm()).append("';");
            }
        }

        ddl.append("\n");
        for (String triggerName : triggers) {
            ddl.append("\n").append(printTrigger(connection, triggerName));
        }
    }

    /**
     * 内部函数编号
     */
    private static final HashMap<Integer,String> INNER_FUNC_NAME;
    static {
        INNER_FUNC_NAME = new HashMap<>();
        INNER_FUNC_NAME.put(-1010,"abs");
        INNER_FUNC_NAME.put(-1015,"mod");
        INNER_FUNC_NAME.put(-173,"substr");
        INNER_FUNC_NAME.put(-201,"ascii");
        INNER_FUNC_NAME.put(-205,"to_number");
        INNER_FUNC_NAME.put(-212,"ceil");
        INNER_FUNC_NAME.put(-213,"floor");
        INNER_FUNC_NAME.put(-224,"degrees");
        INNER_FUNC_NAME.put(-225,"radians");
        INNER_FUNC_NAME.put(-227,"instr");
        INNER_FUNC_NAME.put(-231,"reverse");
        INNER_FUNC_NAME.put(-233,"len");
        INNER_FUNC_NAME.put(-234,"substrb");
        INNER_FUNC_NAME.put(-45,"length");
        INNER_FUNC_NAME.put(-46,"octet_length");
        INNER_FUNC_NAME.put(-47,"char_length");
        INNER_FUNC_NAME.put(-48,"upper");
        INNER_FUNC_NAME.put(-49,"lower");
        INNER_FUNC_NAME.put(-50,"initcap");
    }

    /**
     * 索引分片信息
     * @param connection
     * @param indexname
     * @return
     * @throws SQLException
     */
    private static ArrayList<FragmentInfo> getIndexFragmentInfo(Connection connection, String indexname) throws SQLException {
        String sql = """
                SELECT frag.colno, frag.strategy, frag.evalpos, frag.exprtext, frag.flags, frag.dbspace, frag.partition
                FROM sysfragments frag
                WHERE frag.indexname = ?
                AND frag.fragtype = 'I'
                ORDER BY frag.evalpos
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return new ArrayList<>(runner.query(sql, List.of(indexname), resultSet -> {
            FragmentInfo indexFragmentInfo = new FragmentInfo();
            indexFragmentInfo.setFragName(indexname);
            indexFragmentInfo.setColNo(resultSet.getInt("colno"));
            indexFragmentInfo.setStrategy(resultSet.getString("strategy"));
            indexFragmentInfo.setEvalpos(resultSet.getInt("evalpos"));
            indexFragmentInfo.setExprtext(resultSet.getString("exprtext"));
            indexFragmentInfo.setFlags(resultSet.getInt("flags"));
            indexFragmentInfo.setDbspace(resultSet.getString("dbspace"));
            indexFragmentInfo.setPartition(resultSet.getString("partition"));
            return indexFragmentInfo;
        }));
    }

    /**
     * 生成分片信息
     * @param arrayList
     * @return
     */
    private static String buildFragmentString(ArrayList<FragmentInfo> arrayList) {
        String ddl = " \nFRAGMENT BY ";
        String fragtype = "";
        String fragcolumn = "";         // for List and range, column
        String fraginterval = "";       // for range, interval
        String fragdbslist = "";        // for range, dbspace list or function name;
        String fragdetech = "";         // for range, detech or discard
        int numfragments = 0;           // for range, num

        if (arrayList.size() == 0) {
            return "";
        }
        /**
         * 分片类型是I,表示按表分片规则
         * 分片类型是R(round robin)和E(expression)时，evalpos从0开始；
         * 分片类型是L(List)时，evalpos有-3，exprtext用于定义list字段
         * 分片类型是N(raNge-iNterval (or rolliNg wiNdow))时，需要考虑的就比较多了：
         *     evalpos如果有-4时，colno表示rolling的分片数量，flags值为16表示detach, 8表示discard
         *     evalpos为-3时,exprtext用于定义range字段。  -- 同list
         *     evalpos为-2时,exprtext用于定义interval时长
         *     evalpos为-1时,flags值为4096时：exprtext表示空间；为36874时：exprtext表示使用的函数名称； colno表示有几个空间或者1个函数。
         * evalpos :
         * -1 = 时间间隔分段的数据库空间列表
         * -2 = 时间间隔值
         * -3 = 分段存储键
         * -4 = 滚动窗口分段
         * 按 LIST 的分段存储也使用值 -3。
         */
        for (int i = 0; i < arrayList.size(); i++) {
            if (i == 0) {
                fragtype = arrayList.get(0).getStrategy();
            }
            if (arrayList.get(i).getEvalpos() == 0) {
                break;
            }
            // rolling 窗口数量，及 rolling detach / discard
            if (arrayList.get(i).getEvalpos() == -4) {
                numfragments = arrayList.get(i).getColNo();
                if (arrayList.get(i).getFlags() == 16) {
                    fragdetech = " DETEAH";
                } else if (arrayList.get(i).getFlags() == 8) {
                    fragdetech = " DISCARD";
                }
                // list or range 字段，可能是函数处理后的
            } else if (arrayList.get(i).getEvalpos() == -3) {
                fragcolumn = arrayList.get(i).getExprtext();
            } else if (arrayList.get(i).getEvalpos() == -2) {
                fraginterval = arrayList.get(i).getExprtext();
            } else if (arrayList.get(i).getEvalpos() == -1) {
                // switch ?
                if (arrayList.get(i).getFlags() == 4096) {
                    fragdbslist = arrayList.get(i).getExprtext();        // dbspaces list
                } else if (arrayList.get(i).getFlags() == 36864) {
                    fragdbslist = arrayList.get(i).getExprtext() + "()"; // function
                }
            }
        }
        if ("I".equals(fragtype)) {                     // in dbspace
            ddl = " IN " + arrayList.get(0).getDbspace();
        } else if ("T".equals(fragtype)){               // 索引使用表的分片表达式
            ddl = "";
        } else if ("R".equals(fragtype)){
            ddl = ddl + "ROUND ROBIN \n";
            for(int i=0;i<arrayList.size();i++){
                ddl = ddl + "PARTITION " + arrayList.get(i).getPartition() + " IN " + arrayList.get(i).getDbspace();
                if (i<arrayList.size()-1){
                    ddl = ddl + ",\n";
                }
            }
        } else if ("E".equals(fragtype)){
            ddl = ddl + "EXPRESSION \n";
            for(int i=0;i<arrayList.size();i++){
                ddl = ddl + "PARTITION " + arrayList.get(i).getPartition() + " " + arrayList.get(i).getExprtext() + " IN " + arrayList.get(i).getDbspace();
                if (i<arrayList.size()-1){
                    ddl = ddl + ",\n";
                }
            }
        } else if ("L".equals(fragtype)){
            ddl = ddl + "LIST(" + fragcolumn + ") \n";
            for(int i=0;i<arrayList.size();i++){
                if (arrayList.get(i).getEvalpos() < 0){
                    continue;
                }
                ddl = ddl + "PARTITION " + arrayList.get(i).getPartition() + " " + arrayList.get(i).getExprtext() + " IN " + arrayList.get(i).getDbspace();
                if (i<arrayList.size()-1){
                    ddl = ddl + ",\n";
                }
            }
        } else if ("N".equals(fragtype)){
            ddl = ddl + "RANGE(" + fragcolumn + ") INTERVAL(" + fraginterval + ") \n";
            if (numfragments > 0){
                ddl = ddl + "ROLLING (" + numfragments + " FRAGMENTS) " + fragdetech + " \n";
            }
            ddl = ddl + "STORE IN (" + fragdbslist + ") \n";
            for(int i=0;i<arrayList.size();i++){
                if (arrayList.get(i).getEvalpos() < 0){
                    continue;
                }
                ddl = ddl + "PARTITION " + arrayList.get(i).getPartition() + " " + arrayList.get(i).getExprtext() + " IN " + arrayList.get(i).getDbspace();
                if (i<arrayList.size()-1){
                    ddl = ddl + ",\n";
                }
            }
        }
        return ddl;
    }

    /**
     * 索引列表转字段信息
     * @param idxColsString
     * @param arrayList
     * @return
     */
    private static String getIdxCols(Connection connection,String idxColsString,ArrayList<String> colnameList) throws SQLException {
        // idxColsString: <-234>(1, '2') [1], -3 [1]
        // 索引字段（函数索引字段）以，为分隔符
        // 示例中：<-234>(1, '2', '4') [1] 表示 函数号-234（内置函数），对应的字段是1，-(负值，如有)表示desc排序，'2','4'用于多值参数的函数，[1] 是读取方式（默认该值）；-3表示第三个字段desc排序。
        String idxcols = "";
        String funcname = "";
        String funcparam = "";
        String sortby = ",";
        for (String cols : idxColsString.trim().split(",(?![^()]*+\\))")){      // 以逗号分割，但是不包含括号内的逗号的正则表达式
            String tmpstr = cols.trim();
            if (tmpstr.startsWith("<")){            // function index, 以"<"开头表示函数索引
                // 通过函数序号获取函数名
                int funcnum = Integer.valueOf(tmpstr.substring(1,tmpstr.indexOf(">")));
                if (funcnum < 0) {      // 内部函数
                    funcname = INNER_FUNC_NAME.get(funcnum);
                } else {                // 自定义函数
                    String sql = """
                            select procname from sysprocedures where procid = ?
                            """;
                    SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
                    String procName = runner.queryOne(sql, List.of(funcnum), resultSet -> resultSet.getString("procname"));
                    if (procName != null) {
                        funcname = procName;
                    }
                }
                idxcols = idxcols + funcname + "(";
                // 获取字段 及 排序方式 及多参函数参数
                String tmpstr_func = tmpstr.substring(tmpstr.indexOf("(")+1,tmpstr.indexOf(")"));
                int colno = 0;
                if (tmpstr_func.indexOf(",") > 0){  // 多值参数，有其它参数
                    colno = Integer.valueOf(tmpstr_func.substring(0,tmpstr_func.indexOf(",")));
                    funcparam = tmpstr_func.substring(tmpstr_func.indexOf(",")+1).trim();
                } else {                            // 单值
                    colno = Integer.valueOf(tmpstr_func);
                }
                sortby = (colno<0)?" DESC,":",";
                idxcols = idxcols + getName(colnameList.get(Math.abs(colno) - 1));
                if ("".equals(funcparam)){
                    idxcols = idxcols + ")";
                } else {
                    idxcols = idxcols + "," + funcparam + ")";
                }
                idxcols = idxcols + sortby;
            } else {                                // 普通字段 -3 [1]
                int colno = Integer.valueOf(tmpstr.substring(0,tmpstr.indexOf(" ")));
                sortby = (colno<0)?" DESC,":",";
                idxcols = idxcols + getName(colnameList.get(Math.abs(colno) - 1)) + sortby;
            }
        }
        if (idxcols.length() > 0){
            idxcols = idxcols.substring(0,idxcols.length()-1);
        }
        return idxcols;
    }

    /**
     * 返回索引字段信息，不包含函数索引
     * @param idxColsString
     * @param colnameList
     * @return
     */
    private static String getIdxCols(String idxColsString,ArrayList<String> colnameList) {
        String idxcols = "";
        String sortby = ",";
        for (String cols : idxColsString.trim().split(",(?![^()]*+\\))")){
            String tmpstr = cols.trim();
            // 普通字段 -3 [1]
            int colno = Integer.valueOf(tmpstr.substring(0,tmpstr.indexOf(" ")));
            sortby = (colno<0)?" DESC,":",";
            idxcols = idxcols + getName(colnameList.get(Math.abs(colno) - 1)) + sortby;
        }
        if (idxcols.length() > 0){      // 去除最后的","
            idxcols = idxcols.substring(0,idxcols.length()-1);
        }
        return idxcols;
    }

    /**
     * 通过表id，索引字段列表， 表及字段对应关系，获取键字段列表。
     * @param connection
     * @param tableId
     * @param keyCols
     * @param tableColumnArrayList
     * @return
     * @throws SQLException
     */
    private static String getKeyCols(Connection connection, int tableId, String keyCols, ArrayList<TableColumn> tableColumnArrayList) throws SQLException {
        // keyCols: <-234>(1, '2') [1], -3 [1]
        StringBuilder keyList = new StringBuilder();
        for (String cols : keyCols.trim().split(",(?![^()]*+\\))")){      // 以逗号分割，但是不包含括号内的逗号的正则表达式            
            String tmpstr = cols.trim();
            if (tmpstr.startsWith("<")){     // function index, 以"<"开头表示函数索引
                int funcid = Integer.valueOf(tmpstr.substring(1,tmpstr.indexOf(">")));
                String funcname = "";
                String funcparam = "";
                if (funcid < 0) {      // 内部函数
                    funcname = INNER_FUNC_NAME.get(funcid);
                } else {                // 自定义函数
                    String sql = """
                            select procname from sysprocedures where procid = ?
                            """;
                    SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
                    String procName = runner.queryOne(sql, List.of(funcid), resultSet -> resultSet.getString("procname"));
                    if (procName != null) {
                        funcname = procName;
                    }
                }
                keyList.append(funcname).append("(");
                // 获取字段 及 排序方式 及多参函数参数
                String tmpstr_func = tmpstr.substring(tmpstr.indexOf("(")+1,tmpstr.indexOf(")"));
                int colno = 0;
                if (tmpstr_func.indexOf(",") > 0){  // 多值参数，有其它参数
                    colno = Integer.valueOf(tmpstr_func.substring(0,tmpstr_func.indexOf(",")));
                    funcparam = tmpstr_func.substring(tmpstr_func.indexOf(",")+1).trim();
                } else {                                // 单值
                    colno = Integer.valueOf(tmpstr_func);
                }
                for (TableColumn tableColumn : tableColumnArrayList){
                    if (tableId == tableColumn.getTableId() && Math.abs(colno) == tableColumn.getColumnNo()){
                        keyList.append(getName(tableColumn.getColumnName()));
                        break;
                    }
                }
                if ("".equals(funcparam)){
                    keyList.append(")");
                } else {
                    keyList.append(",").append(funcparam).append(")");
                }
                keyList.append((colno<0)?" DESC,":",");
            } else {                                // 普通字段 -3 [1]               
                int colno = Integer.valueOf(tmpstr.substring(0,tmpstr.indexOf(" ")));
                for (TableColumn tableColumn : tableColumnArrayList){
                    if (tableId == tableColumn.getTableId() && Math.abs(colno) == tableColumn.getColumnNo()){
                        keyList.append(getName(tableColumn.getColumnName()));
                        keyList.append((colno<0)?" DESC,":",");
                        break;
                    }
                }
            }
        }
        if (keyList.length() > 0) {
            keyList.deleteCharAt(keyList.length() - 1);
        }
        return keyList.toString();
    }

    /**
     * 获取指定索引所需的信息
     * @param connection
     * @param indexname
     * @return
     * @throws SQLException
     */
    private static Index getIndexInfo(Connection connection, String indexname, ArrayList<String> colnameList) throws SQLException {
        String sql = """
                SELECT i.owner idxowner,i.idxname,t.owner as tabowner, t.tabname,i.idxtype,i.clustered as idxcluster,i.indexkeys::lvarchar as indexkeys
                FROM sysindices i,systables t
                WHERE i.idxname = ?
                AND i.tabid = t.tabid
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        Index indexInfo = runner.queryOne(sql, List.of(indexname), resultSet -> {
            Index row = new Index();
            row.setName(resultSet.getString("idxname"));
            row.setIndexOwner(resultSet.getString("idxowner"));
            row.setTableName(resultSet.getString("tabname"));
            row.setTableOwner(resultSet.getString("tabowner"));
            row.setIndexType(resultSet.getString("idxtype"));
            row.setIndexCluster(resultSet.getString("idxcluster"));
            row.setIndexCols(resultSet.getString("indexkeys"));
            return row;
        });

        if (indexInfo == null || indexInfo.getName() == null){
            return null;
        }
        indexInfo.setIndexCols(getIdxCols(connection,indexInfo.getIndexCols(),colnameList));
        return indexInfo;
    }

    /**
     * 通过索引名获取表字段列表
     * @param connection
     * @param indexname
     * @return
     * @throws SQLException
     */
    private static ArrayList<String> getColNameListByIndexname(Connection connection, String indexname) throws SQLException {
        String sql = """
                SELECT col.colname
                FROM syscolumns col, sysindexes idx
                WHERE col.tabid = idx.tabid
                AND idx.idxname = ?
                ORDER BY col.colno
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return new ArrayList<>(runner.query(sql, List.of(indexname), resultSet -> resultSet.getString("colname")));
    }

    /**
     * 通过表名获取表字段列表
     * @param connection
     * @param tablename
     * @return
     * @throws SQLException
     */
    private static ArrayList<String> getColNameListByTablename(Connection connection, String tablename) throws SQLException {
        String sql = """
                SELECT col.colname
                FROM syscolumns col, systables t
                WHERE col.tabid = t.tabid
                AND t.tabname = ?
                ORDER BY col.colno
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return new ArrayList<>(runner.query(sql, List.of(tablename), resultSet -> resultSet.getString("colname")));
    }

    /**
     * 打印索引语句
     * @param connection
     * @param indexname
     * @return
     * @throws SQLException
     */
    public static String printIndex(Connection connection, String indexname) throws SQLException {
        ArrayList<String> colNameList = getColNameListByIndexname(connection, indexname);
        Index indexInfo = getIndexInfo(connection, indexname, colNameList);
        if (indexInfo == null || indexInfo.getName() == null){
            return "";
        }
        StringBuilder ddl = new StringBuilder();
        ddl.append("\nCREATE");
        // 索引类型
        if("U".equals(indexInfo.getIndexType())) {
            ddl.append(" UNIQUE INDEX");
        } else if("C".equals(indexInfo.getIndexCluster())){
            ddl.append(" CLUSTER INDEX");
        } else {
            ddl.append(" INDEX");
        }
        // 索引名称  属主.索引名
        ddl.append(" ").append(getName(indexInfo.getName())).append(" ON ");
        // 表名(索引字段（函数索引字段）列表)
        ddl.append(getName(indexInfo.getTableName())).append("(").append(indexInfo.getIndexCols()).append(")");
        // 索引分片规则或者存储
        ddl.append(buildFragmentString(getIndexFragmentInfo(connection, indexname))).append(";");
        return ddl.toString();
    }

    /**
     * 获取触发器信息
     * @param connection
     * @param triggername
     * @return
     * @throws SQLException
     */
    private static Trigger getTriggerInfo(Connection connection, String triggername) throws SQLException {
        String sql = """
                SELECT tri.trigname,tri.mode, bdy.data as trigbody, obj.state
                FROM systriggers tri, systrigbody bdy, sysobjstate obj
                WHERE tri.trigid = bdy.trigid
                AND tri.trigname = obj.name
                AND obj.objtype = 'T'
                AND tri.trigname = ?
                AND (   bdy.datakey = CASE WHEN tri.mode = 'O' THEN 'P' ELSE 'A' END 
                     OR bdy.datakey = CASE WHEN tri.mode = 'O' THEN 'P' ELSE 'D' END
                )
                ORDER BY bdy.datakey DESC,bdy.seqno ASC;
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        List<String[]> rows = runner.query(sql, List.of(triggername), rs -> new String[]{
                rs.getString("trigname"),
                rs.getString("mode"),
                rtrimascii0(rs.getString("trigbody")),
                rs.getString("state")
        });
        Trigger triggerInfo = null;
        StringBuilder triggerBody = new StringBuilder();
        for (String[] row : rows) {
            if (triggerInfo == null) {
                triggerInfo = new Trigger(row[0]);
            }
            if (row[2] != null) {
                triggerInfo.setTriggerMode(row[1]);
                triggerInfo.setIsdisabled(("D".equals(row[3]))?true:false);
                triggerBody.append(row[2]);
            }
        }
        if (triggerInfo != null) {
            triggerInfo.setTriggerBody(triggerBody.toString());
        }
        return triggerInfo;
    }

    /**
     * 打印触发器
     * @param connection
     * @param triggername
     * @return
     * @throws SQLException
     */
    public static String printTrigger(Connection connection, String triggername) throws SQLException {
        Trigger triggerInfo = getTriggerInfo(connection, triggername);
        if (triggerInfo == null || triggerInfo.getName() == null){
            return "";
        }
        String sqlmode = triggerInfo.getTriggerSqlMode();
        int dbVersion = getDataBaseProductVersionNumber(connection);
        boolean displaySqlMode = displaySqlMode(dbVersion);
        StringBuilder ddl = new StringBuilder();
        if(displaySqlMode){
            ddl.append("SET ENVIRONMENT SQLMODE '").append(sqlmode).append("';\n");
        }
        ddl.append(triggerInfo.getTriggerBody());
        // Oracle模式也是这么写？？
        if (triggerInfo.isIsdisabled()){
            ddl.append("SET TRIGGERS ").append(triggerInfo.getName()).append(" DISABLED;\n");
        }
        return ddl.toString();
    }

    /**
     * 获取序列信息
     * @param connection
     * @param sequencename
     * @param delimident
     * @return
     * @throws SQLException
     */
    private static Sequence getSequenceInfo(Connection connection, String sequencename) throws SQLException {
        String sql = """
                SELECT t.owner AS seqowner,t.tabname AS seqname,seq.start_val AS startval,
                  seq.inc_val AS incval,seq.max_val AS maxval,seq.min_val AS minval,
                  seq.cycle AS iscycle, seq.cache AS cache,seq.order AS isorder, t.flags
                FROM systables t, syssequences seq
                WHERE t.tabid = seq.tabid
                AND t.tabname = ?
                AND t.tabtype = 'Q'
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.queryOne(sql, List.of(sequencename), rs -> {
            Sequence sequenceInfo = new Sequence(rs.getString("seqname"));
            sequenceInfo.setSeqOwner(rs.getString("seqowner"));
            sequenceInfo.setStartVal(rs.getLong("startval"));
            sequenceInfo.setIncVal(rs.getLong("incval"));
            sequenceInfo.setMaxVal(rs.getLong("maxval"));
            sequenceInfo.setMinVal(rs.getLong("minval"));
            sequenceInfo.setIsCycle(rs.getString("iscycle"));
            sequenceInfo.setCache(rs.getInt("cache"));
            sequenceInfo.setIsOrder(rs.getString("isorder"));
            sequenceInfo.setFlags(rs.getInt("flags"));
            return sequenceInfo;
        });
    }

    /**
     * 打印序列
     * @param connection
     * @param synonymname
     * @param delimident
     * @return
     * @throws SQLException
     */
    public static String printSequence(Connection connection, String sequencename) throws SQLException {
        Sequence sequenceInfo = getSequenceInfo(connection, sequencename);
        if (sequenceInfo == null || sequenceInfo.getName() == null){
            return "";
        }
        String sqlmode = sequenceInfo.getSequenceSqlMode();
        int dbVersion = getDataBaseProductVersionNumber(connection);
        boolean displaySqlMode = displaySqlMode(dbVersion);
        StringBuilder ddl = new StringBuilder();
        if(displaySqlMode){
            ddl.append("SET ENVIRONMENT SQLMODE '").append(sqlmode).append("';\n");
        }
        ddl.append("CREATE SEQUENCE ");
        // owner, 不再增加owner
        // ddl.append("\"").append(sequenceInfo.getSeqOwner().trim()).append("\".");
        // seqname
        ddl.append(getName(sequenceInfo.getName()));
        // start with
        if(sequenceInfo.getStartVal() > 0){
            ddl.append(" START WITH ").append(sequenceInfo.getStartVal());
        }
        // increment by
        ddl.append(" INCREMENT BY ").append(sequenceInfo.getIncVal());
        // maxvalue
        ddl.append(" MAXVALUE ").append(sequenceInfo.getMaxVal());
        // minvalue
        ddl.append(" MINVALUE ").append(sequenceInfo.getMinVal());
        // cycle
        if ("1".equals(sequenceInfo.getIsCycle())){
            ddl.append(" CYCLE");
        }
        // cache
        if (sequenceInfo.getCache() > 0){
            ddl.append(" CACHE ").append(sequenceInfo.getCache());
        } else {
            ddl.append(" NOCACHE");
        }
        // order
        if ("1".equals(sequenceInfo.getIsOrder())){
            ddl.append(" ORDER");
        } else {
            ddl.append(" NOORDER");
        }

        return ddl.append(";").toString();
    }

    /**
     * 获取同义词信息
     * @param connection
     * @param synonymname
     * @param delimident
     * @return
     * @throws SQLException
     */
    private static Synonym getSynonymInfo(Connection connection, String synonymname) throws SQLException {
        String sql = """
                select t1.tabtype AS syntype,t1.owner AS synowner,t1.tabname AS synname,
                    s.servername AS rservername,s.dbname AS rdbname,s.owner AS rowner,s.tabname AS rtabname,
                    syn.owner AS lowner,syn.tabname AS ltabname, t1.flags
                from syssyntable s
                LEFT JOIN systables syn ON s.btabid = syn.tabid, systables t1
                WHERE s.tabid = t1.tabid
                AND t1.tabname = ?
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return runner.queryOne(sql, List.of(synonymname), rs -> {
            Synonym synonymInfo = new Synonym(rs.getString("synname"));
            synonymInfo.setSynType(rs.getString("syntype"));
            synonymInfo.setSynOwner(rs.getString("synowner"));
            synonymInfo.setRServerName(rs.getString("rservername"));
            synonymInfo.setRDbName(rs.getString("rdbname"));
            synonymInfo.setROwner(rs.getString("rowner"));
            synonymInfo.setRTabName(rs.getString("rtabname"));
            synonymInfo.setLOwner(rs.getString("lowner"));
            synonymInfo.setLTabName(rs.getString("ltabname"));
            synonymInfo.setFlags(rs.getInt("flags"));
            return synonymInfo;
        });
    }

    /**
     * 打印同义词
     * @param connection
     * @param synonymname
     * @param delimident
     * @return
     * @throws SQLException
     */
    public static String printSynonym(Connection connection, String synonymname) throws SQLException {
        Synonym synonymInfo = getSynonymInfo(connection, synonymname);
        if (synonymInfo == null || synonymInfo.getName() == null){
            return "";
        }
        String sqlmode = synonymInfo.getSynonymSqlMode();
        int dbVersion = getDataBaseProductVersionNumber(connection);
        boolean displaySqlMode = displaySqlMode(dbVersion);
        StringBuilder ddl = new StringBuilder();
        if(displaySqlMode){
            ddl.append("SET ENVIRONMENT SQLMODE '").append(sqlmode).append("';\n");
        }
        ddl.append("CREATE ");
        // private or public
        if ("P".equals(synonymInfo.getSynType())){   // S = Public synonym, P = Private synonym
            ddl.append("PRIVATE ");
        }
        // owner 不再打印
        // ddl.append("SYNONYM \"").append(synonymInfo.getSynOwner().trim()).append("\".");
        // name
        ddl.append("SYNONYM ").append(getName(synonymInfo.getName())).append(" FOR ");
        // 同义词指向remote
        if (synonymInfo.getRTabName() != null){      // 同义远程表
            // remote dbname
            ddl.append(synonymInfo.getRDbName().trim());
            // remote servername, can other db
            if (synonymInfo.getRServerName() != null && ! "".equals(synonymInfo.getRServerName())){
                ddl.append("@").append(synonymInfo.getRServerName().trim());
            }
            // remote table owner
            // ddl.append(":\"").append(synonymInfo.getROwner().trim()).append("\".");
            if ("Oracle".equals(sqlmode)){
                ddl.append(".");
            } else {
                ddl.append(":");
            }
            // remote table name
            ddl.append(getName(synonymInfo.getRTabName()));
            // 同义词指向本库
        } else {
            // owner 不再打印
            // ddl.append("\"").append(synonymInfo.getLOwner().trim()).append("\".");
            // table name
            ddl.append(getName(synonymInfo.getLTabName()));
        }
        return ddl.append(";").toString();
    }

    /**
     * 获取视图定义信息
     * @param connection
     * @param viewname
     * @param delimident
     * @return
     * @throws SQLException
     */
    private static View getViewInfo(Connection connection, String viewname) throws SQLException {
        String sql = """
                select v.viewtext as viewtext, t.flags
                from systables t,sysviews v
                where t.tabid = v.tabid
                and t.tabtype = 'V'
                and t.tabname = ?
                order by v.seqno
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        List<String[]> rows = runner.query(sql, List.of(viewname), rs -> new String[]{
                rtrimascii0(rs.getString("viewtext")),
                String.valueOf(rs.getInt("flags"))
        });
        StringBuilder viewBody = new StringBuilder();
        int flags = 0;
        for (String[] row : rows) {
            if (row[0] != null) {
                viewBody.append(row[0]);
            }
            flags = Integer.parseInt(row[1]);
        }
        if (viewBody.length() == 0){
            return null;
        }
        View viewInfo = new View(viewname);
        viewInfo.setViewBoday(viewBody.toString());
        viewInfo.setFlags(flags);
        return viewInfo;
    }

    /**
     * 打印视图定义
     * @param connection
     * @param viewname
     * @param delimident
     * @return
     * @throws SQLException
     */
    public static String printView(Connection connection, String viewname) throws SQLException {
        View viewInfo = getViewInfo(connection, viewname);
        if (viewInfo == null || viewInfo.getName() == null){
            return "";
        }
        String sqlmode = viewInfo.getViewSqlMode();
        int dbVersion = getDataBaseProductVersionNumber(connection);
        boolean displaySqlMode = displaySqlMode(dbVersion);
        StringBuilder ddl = new StringBuilder();
        if(displaySqlMode){
            ddl.append("SET ENVIRONMENT SQLMODE '").append(sqlmode).append("';\n");
        }
        ddl.append(viewInfo.getViewBoday());
        return ddl.toString();
    }

    /**
     * 获取存储过程、函数信息定义，可能存在多个
     * @param connection
     * @param procname
     * @param delimident
     * @return
     * @throws SQLException
     */
    private static ArrayList<Procedure> getProcInfo(Connection connection, String procdefine) throws SQLException {
        ArrayList<Procedure> procedureArrayList = new ArrayList<>();
        String sql;
        StringBuilder procBody = new StringBuilder();
        String[] tmpProc = null;
        String procname = null;
        int numargs = 0;
        String paramtypes = "";
        int exists = 0;
        int procFlags = 0;
        int procId = 0;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);

        // procdefine: func1(integer,integer)
        tmpProc = procdefine.split("\\(");
        procname = tmpProc[0].trim();
        if (tmpProc.length == 1){
            paramtypes = "";
        } else {
            tmpProc = tmpProc[1].toLowerCase().split("\\)");
            tmpProc = tmpProc[0].split(",");
            numargs = tmpProc.length;
            for(String str : tmpProc){
                paramtypes = paramtypes + "," + str.trim();
            }
            paramtypes = paramtypes.substring(1);
        }
        sql = """
            select count(*) as isexists from sysprocedures where procname = ?
            """;
        Integer existsValue = runner.queryOne(sql, List.of(procname), rs -> rs.getInt(1));
        exists = existsValue == null ? 0 : existsValue;
        if (exists == 0){                 // 不存在
            return null;
        } else if (exists == 1 || (exists > 1 && numargs == 0)){  // 没有同名函数 或 同名函数但没输入参数时
            sql = """
                select p.procname,b.seqno,b.data procbody,p.procflags,p.procid 
                from sysprocedures p, sysprocbody b 
                where p.procid = b.procid 
                and p.procname = ? 
                and p.mode in ('O','o') 
                and b.datakey = 'T' 
                order by p.procid ASC, b.seqno ASC;
                """;
            List<String[]> rows = runner.query(sql, List.of(procname), rs -> new String[]{
                    rtrimascii0(rs.getString("procbody")),
                    String.valueOf(rs.getInt("procflags")),
                    String.valueOf(rs.getInt("procid"))
            });
            for (String[] row : rows) {
                if (procId > 0 && procId != Integer.parseInt(row[2])){
                    Procedure procedureInfo = new Procedure(procname);
                    procedureInfo.setProcBoday(procBody.toString().trim());
                    procedureInfo.setProcFlags(procFlags);
                    procedureInfo.setProcId(procId);
                    procedureArrayList.add(procedureInfo);
                    procBody.setLength(0);
                    if (row[0] != null) {
                        procBody.append(row[0]);
                    }
                    procFlags = Integer.parseInt(row[1]);
                    procId = Integer.parseInt(row[2]);
                } else {
                    if (row[0] != null) {
                        procBody.append(row[0]);
                    }
                    procFlags = Integer.parseInt(row[1]);
                    procId = Integer.parseInt(row[2]);
                }                
            }
            if (procId > 0){
                Procedure procedureInfo = new Procedure(procname);
                procedureInfo.setProcBoday(procBody.toString().trim());
                procedureInfo.setProcFlags(procFlags);
                procedureInfo.setProcId(procId);
                procedureArrayList.add(procedureInfo);
            }

        } else if (exists > 1 && numargs > 0){  // 指明了函数参数               
            sql = """
                select p.procname,b.seqno,b.data procbody,p.procflags,p.procid
                from sysprocedures p, sysprocbody b
                where p.procid = b.procid
                and p.procname = ?
                and p.mode in ('O','o') 
                and p.numargs =  ?
                and rtn_param_out(paramtypes) = ?
                and b.datakey = 'T'
                order by b.seqno
                """;
            List<String[]> rows = runner.query(sql, List.of(procname, numargs, paramtypes), rs -> new String[]{
                rtrimascii0(rs.getString("procbody")),
                String.valueOf(rs.getInt("procflags")),
                String.valueOf(rs.getInt("procid"))
            });
            for (String[] row : rows) {
                if (row[0] != null) {
                    procBody.append(row[0]);
                }
                procFlags = Integer.parseInt(row[1]);
                procId = Integer.parseInt(row[2]);
                Procedure procedureInfo = new Procedure(procname);
                procedureInfo.setProcBoday(procBody.toString().trim());
                procedureInfo.setProcFlags(procFlags);
                procedureInfo.setProcId(procId);
                procedureArrayList.add(procedureInfo);
            }               
        }
        return procedureArrayList;
    }

    /**
     * 打印存储过程信息
     * @param connection
     * @param procdefine
     * @param delimident
     * @return
     * @throws SQLException
     */
    public static String printProcedure(Connection connection, String procdefine) throws SQLException {
        ArrayList<Procedure> procedureArrayList = getProcInfo(connection, procdefine);
        int dbVersion = getDataBaseProductVersionNumber(connection);
        boolean displaySqlMode = displaySqlMode(dbVersion);
        StringBuilder ddl = new StringBuilder();
        if (procedureArrayList == null || procedureArrayList.size() == 0){
            return null;
        }
        for(Procedure procedure : procedureArrayList){
            String sqlmode = procedure.getProcSqlMode();
            if (displaySqlMode){
                ddl.append("SET ENVIRONMENT SQLMODE '").append(sqlmode).append("';\n");
            }
            ddl.append(procedure.getProcBoday()).append("\n");
        }
        return ddl.toString();
    }

    /**
     * 打印整个库的结构：顺序暂定：函数->存储过程->表（含主键、索引、约束）->同义词（等）-> 序列 -> 视图（可能有依赖关系，先后顺序）
     * @param connection
     * @param databasename
     * @return
     * @throws SQLException
     */
    public static String printDatabase(Connection connection,String databasename) throws SQLException {
        // 顺序暂定：函数->存储过程->表（含主键、索引、约束）->同义词（等）-> 序列 -> 视图（可能有依赖关系，先后顺序）
        StringBuilder ddl = new StringBuilder();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String datestr = formatter.format(new Date(System.currentTimeMillis()));
        String preDatabase = getActiveDbname(connection);
        String productversion = getDataBaseProductVersion(connection);
        int dbVersion = getDataBaseProductVersionNumber(connection);
        boolean displaysqlmode = displaySqlMode(dbVersion);
        String sqlstr = null;
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;
        ddl.append("-- ### product version : ").append(productversion).append("\n");
        ddl.append("-- ### export database : ").append(databasename).append("\n");
        ddl.append("-- ### export datetime : ").append(datestr).append("\n");
        // 变更激活库为当前
        if (! databasename.equals(preDatabase)){
            setActiveDbname(connection,databasename);
        }

        // 1, 导出自定义函数和存储过程。 procname, procbody, procflags  
        sqlstr = """
                select procname,procid,procflags,seqno,procbody from ( 
                    select p.procname,p.procid,p.procflags,b.seqno,b.data as procbody 
                    from sysprocedures p, sysprocbody b  
                    where p.procid = b.procid 
                    and p.mode in ('O','o') 
                    and b.datakey = 'T' 
                    and p.isproc = 'f' 
                    order by p.procid asc,b.seqno asc
                ) 
                union all 
                select procname,procid,procflags,seqno,procbody from ( 
                    select p.procname,p.procid,p.procflags,b.seqno,b.data as procbody 
                    from sysprocedures p, sysprocbody b  
                    where p.procid = b.procid 
                    and p.mode in ('O','o') 
                    and b.datakey = 'T' 
                    and p.isproc = 't' 
                    order by p.procid asc,b.seqno asc
                );
                """;
        preparedStatement = connection.prepareStatement(sqlstr);
        resultSet = preparedStatement.executeQuery();

        ArrayList<Procedure> procedureInfoArrayList = new ArrayList<>();
        String procname = null;
        int procflags = 0;
        String procbody = null;
        // 初始化参数
        if (resultSet.next()){
            procname = resultSet.getString("procname");
            procflags = resultSet.getInt("procflags");
            procbody = resultSet.getString("procbody");
        }
        while (resultSet.next()){
            if (resultSet.getInt("seqno") == 1){
                Procedure procedureInfo = new Procedure(procname);
                procedureInfo.setProcFlags(procflags);
                procedureInfo.setProcBoday(trim(procbody));
                procedureInfoArrayList.add(procedureInfo);
                procname = resultSet.getString("procname");
                procflags = resultSet.getInt("procflags");
                procbody = resultSet.getString("procbody");
            } else {
                procbody = procbody + resultSet.getString("procbody");
            }
        }
        if (procname != null){
            Procedure procedureInfo = new Procedure(procname);
            procedureInfo.setProcFlags(procflags);
            procedureInfo.setProcBoday(trim(procbody));
            procedureInfoArrayList.add(procedureInfo);
        }
        // 打印 函数和存储过程
        ddl = ddl.append("-- ### START: output function and procedure.\n");
        for(int i=0;i<procedureInfoArrayList.size();i++){
            ddl.append("-- function or procedure : ").append(procedureInfoArrayList.get(i).getName()).append("\n");
            if (displaysqlmode){
                ddl.append("SET ENVIRONMENT SQLMODE '").append(procedureInfoArrayList.get(i).getProcSqlMode()).append("';\n");
            }
            ddl.append(procedureInfoArrayList.get(i).getProcBoday()) ;
            if (! procedureInfoArrayList.get(i).getProcBoday().endsWith(";")){
                ddl.append(";");
            }
            if ("Oracle".equals(procedureInfoArrayList.get(i).getProcSqlMode())){
                ddl.append("\n/");
            }
            ddl.append("\n\n");
        }
        ddl.append("-- ### FINISH: output function and procedure.\n\n");

        // 2, 导出表结构, TODO: 表每次都查询一次syscolumns? 该成本太高！！
        ddl.append("-- ### START: output tables.\n");
        ArrayList<Table> tableInfoArrayList = new ArrayList<>();
        ArrayList<Integer> tableInfoTabidArrayList = new ArrayList<>();
        String jdbcVersion = connection.getMetaData().getDriverVersion();
        int dbversion = Integer.parseInt(jdbcVersion.substring(0,1));
        // 所有表信息，去除物化视图 "mtab$_"
        sqlstr = """
                select t.tabname,dbinfo('dbname') as tablecatalog, t.owner as tableowner,t.locklevel as locktype,
                t.fextsize as firstextsize, t.nextsize as nextextsize, t.tabtype as tabletype,t.flags as tableflags, t.tabid 
                from systables t 
                where t.tabid > (select tabid from systables where tabname = ' VERSION') 
                and t.tabtype = 'T' 
                and t.tabname not like 'mtab$\\_%' 
                order by t.tabid asc;
                """;

        preparedStatement = connection.prepareStatement(sqlstr);
        resultSet = preparedStatement.executeQuery();
        while(resultSet.next()){
            Table tableInfo = new Table(resultSet.getString("tabname"));
            tableInfo.setTableCatalog(resultSet.getString("tablecatalog"));
            tableInfo.setTableOwner(trim(resultSet.getString("tableowner")));
            tableInfo.setLockType(resultSet.getString("locktype"));
            tableInfo.setFirstExtSize(resultSet.getInt("firstextsize"));
            tableInfo.setNextExtSize(resultSet.getInt("nextextsize"));
            tableInfo.setTableTypeCode(resultSet.getString("tabletype"));
            tableInfo.setFlags(resultSet.getInt("tableflags"));
            tableInfo.setDbVersion(dbversion);
            tableInfoArrayList.add(tableInfo);
            // 尝试：创建一个列表，将转换成数组，用于快速查找
            tableInfoTabidArrayList.add(resultSet.getInt("tabid"));
        }

        // TODO: 每个表调用一次查询，效率上似乎并不会高。
        for(int i=0;i<tableInfoArrayList.size();i++){
            // 输出信息中含 表主体（含约束、主键），外部表定义信息（如有），区段信息。
            ddl.append("-- table : ").append(tableInfoArrayList.get(i).getTableOwner()).append(".").append(tableInfoArrayList.get(i).getName()).append("\n");
            ddl.append(buildTableSql(connection,tableInfoArrayList.get(i),displaysqlmode));
            if ("E".equals(tableInfoArrayList.get(i).getTableTypeCode())) {
                ddl.append(buildExtTableSql(connection, tableInfoArrayList.get(i)));
            } else {
                ddl.append(buildTableSqlExtent(connection, tableInfoArrayList.get(i)));
            }
            ddl.append("\n");
        }
        ddl.append("-- ### FINISH: output tables.\n\n");

         // 3 同义词
        ddl.append("-- ### START: output synonym.\n");
        ArrayList<Synonym> synonymInfoArrayList = new ArrayList<>();
        sqlstr = "select t1.tabtype AS syntype,t1.owner AS synowner,t1.tabname AS synname, " +
                "    s.servername AS rservername,s.dbname AS rdbname,s.owner AS rowner,s.tabname AS rtabname, " +
                "    syn.owner AS lowner,syn.tabname AS ltabname, t1.flags " +
                "from syssyntable s " +
                "LEFT JOIN systables syn ON s.btabid = syn.tabid, systables t1 " +
                "WHERE s.tabid = t1.tabid;";
        preparedStatement = connection.prepareStatement(sqlstr);
        resultSet = preparedStatement.executeQuery();
        while (resultSet.next()){
            Synonym synonymInfo = new Synonym(resultSet.getString("synname"));
            synonymInfo.setSynType(resultSet.getString("syntype"));
            synonymInfo.setSynOwner(trim(resultSet.getString("synowner")));
            synonymInfo.setRServerName(resultSet.getString("rservername"));
            synonymInfo.setRDbName(resultSet.getString("rdbname"));
            synonymInfo.setROwner(trim(resultSet.getString("rowner")));
            synonymInfo.setRTabName(resultSet.getString("rtabname"));
            synonymInfo.setLOwner(trim(resultSet.getString("lowner")));
            synonymInfo.setLTabName(resultSet.getString("ltabname"));
            synonymInfo.setFlags(resultSet.getInt("flags"));
            synonymInfoArrayList.add(synonymInfo);
        }
        for(int i=0;i<synonymInfoArrayList.size();i++){
            ddl.append("-- synonym : " + synonymInfoArrayList.get(i).getName()).append(".").append(synonymInfoArrayList.get(i).getName()).append("\n");
            if (displaysqlmode){
                ddl.append("SET ENVIRONMENT SQLMODE '").append(synonymInfoArrayList.get(i).getSynonymSqlMode()).append("';\n");
            }
            ddl.append("CREATE ");
            // private or public
            if ("P".equals(synonymInfoArrayList.get(i).getSynType())){   // S = Public synonym, P = Private synonym
                ddl.append("PRIVATE ");
            }
            ddl.append("SYNONYM ").append(getName(synonymInfoArrayList.get(i).getName())).append(" FOR ");
            // 同义词指向remote
            if (synonymInfoArrayList.get(i).getRTabName() != null){      // 同义远程表
                // remote dbname
                ddl.append(trim(synonymInfoArrayList.get(i).getRDbName()));
                // remote servername, can other db
                if (synonymInfoArrayList.get(i).getRServerName() != null && ! "".equals(synonymInfoArrayList.get(i).getRServerName())){
                    ddl.append("@").append(trim(synonymInfoArrayList.get(i).getRServerName()));
                }
                ddl.append(("Oracle".equals(synonymInfoArrayList.get(i).getSynonymSqlMode()))?".":":");
                ddl.append(getName(synonymInfoArrayList.get(i).getRTabName()));
                // 同义词指向本库
            } else {
                ddl.append(getName(synonymInfoArrayList.get(i).getLTabName()));
            }
            ddl.append("\n\n");
        }
        ddl.append("-- ### FINISH: output synonym.\n\n");

        // 4 序列
        ddl.append("-- ### START: output sequence.\n");
        ArrayList<Sequence> sequenceInfoArrayList = new ArrayList<>();
        sqlstr = "SELECT t.owner AS seqowner,t.tabname AS seqname,seq.start_val AS startval, " +
                "  seq.inc_val AS incval,seq.max_val AS maxval,seq.min_val AS minval, " +
                "  seq.cycle AS iscycle, seq.cache AS cache,seq.order AS isorder, t.flags  " +
                "FROM systables t, syssequences seq " +
                "WHERE t.tabid = seq.tabid;";
        preparedStatement = connection.prepareStatement(sqlstr);
        resultSet = preparedStatement.executeQuery();
        while (resultSet.next()){
            Sequence sequenceInfo = new Sequence(resultSet.getString("seqname"));
            sequenceInfo.setSeqOwner(trim(resultSet.getString("seqowner")));
            sequenceInfo.setStartVal(resultSet.getLong("startval"));
            sequenceInfo.setIncVal(resultSet.getLong("incval"));
            sequenceInfo.setMaxVal(resultSet.getLong("maxval"));
            sequenceInfo.setMinVal(resultSet.getLong("minval"));
            sequenceInfo.setIsCycle(resultSet.getString("iscycle"));
            sequenceInfo.setCache(resultSet.getLong("cache"));;
            sequenceInfo.setIsOrder(resultSet.getString("isorder"));
            sequenceInfo.setFlags(resultSet.getInt("flags"));
            sequenceInfoArrayList.add(sequenceInfo);
        }
        for (int i=0;i<sequenceInfoArrayList.size();i++){
            ddl.append("-- sequence : " + sequenceInfoArrayList.get(i).getSeqOwner()).append(".").append(sequenceInfoArrayList.get(i).getName()).append("\n");
            if (displaysqlmode){
                ddl.append("SET ENVIRONMENT SQLMODE '").append(sequenceInfoArrayList.get(i).getSequenceSqlMode()).append("';\n");
            }
            ddl.append( "CREATE SEQUENCE ").append(getName(sequenceInfoArrayList.get(i).getName()));
            // start with
            if(sequenceInfoArrayList.get(i).getStartVal() > 0){
                ddl.append(" START WITH ").append(sequenceInfoArrayList.get(i).getStartVal());
            }
            // increment by
            ddl.append(" INCREMENT BY ").append(sequenceInfoArrayList.get(i).getIncVal());
            // maxvalue
            ddl.append(" MAXVALUE ").append(sequenceInfoArrayList.get(i).getMaxVal());
            // minvalue
            ddl.append(" MINVALUE ").append(sequenceInfoArrayList.get(i).getMinVal());
            // cycle
            if ("1".equals(sequenceInfoArrayList.get(i).getIsCycle())){
                ddl.append(" CYCLE");
            }
            // cache
            if (sequenceInfoArrayList.get(i).getCache() > 0){
                ddl.append(" CACHE " ).append(sequenceInfoArrayList.get(i).getCache());
            } else {
                ddl.append(" NOCACHE");
            }
            // order
            if ("1".equals(sequenceInfoArrayList.get(i).getIsOrder())){
                ddl.append(" ORDER");
            } else {
                ddl.append(" NOORDER");
            }
            ddl.append(";\n\n");
        }
        ddl.append("-- ### FINISH: output sequence.\n\n");

        // 5 视图
        ddl.append("-- ### START: output view.\n");
        ArrayList<View> viewInfoArrayList = new ArrayList<>();
        sqlstr = """
            select t.tabname as viewname,v.seqno,v.viewtext as viewtext, t.flags 
            from systables t,sysviews v  
            where t.tabid = v.tabid 
            AND t.tabid > (SELECT tabid FROM systables WHERE tabname = ' VERSION') 
            and t.tabtype = 'V' 
            order by t.tabid ASC, v.seqno ASC;
            """;
        preparedStatement = connection.prepareStatement(sqlstr);
        resultSet = preparedStatement.executeQuery();
        String viewname = null;
        String viewtext = null;
        int viewflags = 0;
        if (resultSet.next()){
            viewname = resultSet.getString("viewname");
            viewtext = resultSet.getString("viewtext");
            viewflags = resultSet.getInt("flags");
        }
        while (resultSet.next()){
            if (resultSet.getInt("seqno") == 0){
                View viewInfo = new View(viewname);
                viewInfo.setViewBoday(trim(viewtext));
                viewInfo.setFlags(viewflags);
                viewInfoArrayList.add(viewInfo);
                viewname = resultSet.getString("viewname");
                viewtext = resultSet.getString("viewtext");
                viewflags = resultSet.getInt("flags");
            } else {
                viewtext = viewtext + resultSet.getString("viewtext");
            }
        }
        if (viewname != null){
            View viewInfo = new View(viewname);
            viewInfo.setViewBoday(trim(viewtext));
            viewInfo.setFlags(viewflags);
            viewInfoArrayList.add(viewInfo);
        }
        for (int i=0;i<viewInfoArrayList.size();i++){
            ddl.append("-- view : ").append(viewInfoArrayList.get(i).getName()).append("\n");
            if (displaysqlmode){
                ddl.append("SET ENVIRONMENT SQLMODE '").append(viewInfoArrayList.get(i).getViewSqlMode()).append("';\n");
            }
            ddl.append(viewInfoArrayList.get(i).getViewBoday());
            ddl.append("\n");
        }
        ddl.append("-- ### FINISH: output view.\n\n");

        // 6 索引，需要字段列表 TODO：索引分片及存储规则
        // 单独生成 tableid, tablename, columnno, columnname 列表。 索引和外键共用
        ArrayList<TableColumn> tableColumnArrayList = new ArrayList<>();
        sqlstr = """
            SELECT c.tabid,t.tabname,c.colno,c.colname
            FROM syscolumns c, systables t
            WHERE c.tabid = t.tabid 
            AND c.tabid > (SELECT tabid FROM systables WHERE tabname = ' VERSION')
            ORDER BY c.tabid ASC,colno ASC;
            """;
        preparedStatement = connection.prepareStatement(sqlstr);
        resultSet = preparedStatement.executeQuery();
        while (resultSet.next()){
            TableColumn tableColumn = new TableColumn();
            tableColumn.setTableId(resultSet.getInt("tabid"));
            tableColumn.setTableName(resultSet.getString("tabname"));
            tableColumn.setColumnId(resultSet.getInt("colno"));
            tableColumn.setColumnName(resultSet.getString("colname"));
            tableColumnArrayList.add(tableColumn);
        }

        // 基于表字段
        ddl.append("-- ### START: output index.\n");
        ArrayList<Index> indexesArrayList = new ArrayList<>();
        sqlstr = """
            SELECT i.owner idxowner,i.idxname,t.owner as tabowner, t.tabid, t.tabname, t.tabtype,
                i.idxtype,i.clustered as idxcluster,cast(i.indexkeys AS lvarchar) as indexkeys
            FROM sysindices i,systables t
            WHERE i.tabid = t.tabid
            AND i.tabid > (SELECT tabid FROM systables WHERE tabname = ' VERSION')
            AND substr(i.idxname,1,1) != ' '
            ORDER BY i.tabid ASC;   
            """;
        preparedStatement = connection.prepareStatement(sqlstr);
        resultSet = preparedStatement.executeQuery();
        while (resultSet.next()){
            Index index = new Index(resultSet.getString("idxname"));
            index.setIndexOwner(resultSet.getString("idxowner"));
            index.setTableOwner(resultSet.getString("tabowner"));
            index.setTableName(resultSet.getString("tabname"));
            index.setTableId(resultSet.getInt("tabid"));
            index.setTableType(resultSet.getString("tabtype"));
            index.setIndexType(resultSet.getString("idxtype"));
            index.setIndexCluster(resultSet.getString("idxcluster"));
            index.setIndexCols(resultSet.getString("indexkeys"));
            indexesArrayList.add(index);
        }

        for (int i=0; i<indexesArrayList.size();i++){
            if ("U".equals(indexesArrayList.get(i).getIdxtype())){
                ddl.append("CREATE UNIQUE INDEX ");
            } else if ("C".equals(indexesArrayList.get(i).getIdxtype())){
                ddl.append("CREATE CLUSTER INDEX ");
            } else {
                ddl.append("CREATE INDEX ");
            }
            ddl.append(getName(indexesArrayList.get(i).getName())).append(" ON ");
            ddl.append(getName(indexesArrayList.get(i).getTableName())).append("(");
            // 索引字段列表
            ddl.append(getKeyCols(connection, indexesArrayList.get(i).getTableId(), indexesArrayList.get(i).getCols(), tableColumnArrayList));

            ddl.append(");\n");
        }

        ddl.append("-- ### FINISH: output index.\n\n");

        // 7 外键，需要字段列表
        ddl.append("-- ### START: output foreigen key.\n");
        String patternConstraint = "^[cur]\\d+_\\d+";
        ArrayList<ForeignKeyInfo> foreignKeyInfoArrayList = new ArrayList<>();
        sqlstr = """
            SELECT fk_c.constrname,fk_t.owner AS fkowner, fk_t.tabid AS fktabid, fk_t.tabname AS fktabname, 
                cast(fk_i.indexkeys as lvarchar) AS fk_keys, fk_c.idxname as fkidxname, pk_t.owner AS pkowner, 
                pk_t.tabid AS pktabid, pk_t.tabname AS pktabname, cast(pk_i.indexkeys as lvarchar) AS pk_keys,
                pk_i.idxname as pkidxname, obj.state, fk_t.flags
            FROM sysconstraints fk_c, systables fk_t, sysindices fk_i,sysreferences fk_r,
                 sysconstraints pk_c, systables pk_t, sysindices pk_i,sysobjstate obj
            WHERE fk_c.tabid = fk_t.tabid
            AND fk_c.constrname = obj.name
            AND obj.objtype = 'C'
            AND fk_c.constrtype = 'R'
            AND fk_c.idxname = fk_i.idxname
            AND fk_c.constrid = fk_r.constrid
            AND fk_r.PRIMARY = pk_c.constrid
            AND pk_c.tabid = pk_t.tabid
            AND pk_c.idxname = pk_i.idxname
            ORDER BY fk_t.tabid ASC; 
            """;
        preparedStatement = connection.prepareStatement(sqlstr);
        resultSet = preparedStatement.executeQuery();
        while (resultSet.next()) {
            ForeignKeyInfo foreignKeyInfo = new ForeignKeyInfo();
            foreignKeyInfo.setFkName(resultSet.getString("constrname"));
            foreignKeyInfo.setFkOwner(resultSet.getString("fkowner"));
            foreignKeyInfo.setFkTabid(resultSet.getInt("fktabid"));
            foreignKeyInfo.setFkTabname(resultSet.getString("fktabname"));
            foreignKeyInfo.setFkCols(resultSet.getString("fk_keys"));
            foreignKeyInfo.setFkIdxName(resultSet.getString("fkidxname"));
            foreignKeyInfo.setPkOwner(resultSet.getString("pkowner"));
            foreignKeyInfo.setPkTabid(resultSet.getInt("pktabid"));
            foreignKeyInfo.setPkTabname(resultSet.getString("pktabname"));
            foreignKeyInfo.setPkCols(resultSet.getString("pk_keys"));
            foreignKeyInfo.setPkIdxName(resultSet.getString("pkidxname"));
            foreignKeyInfo.setIsdisabled(("D".equals(resultSet.getString("state")))?true:false);
            foreignKeyInfo.setFlags(resultSet.getInt("flags"));
            foreignKeyInfoArrayList.add(foreignKeyInfo);
        }
        for (int i=0;i<foreignKeyInfoArrayList.size();i++){
            if (displaysqlmode){
                ddl.append("SET ENVIRONMENT SQLMODE '");
                ddl.append(foreignKeyInfoArrayList.get(i).getForeignKeyModeFunc()).append("';\n");
            }
            ddl.append("ALTER TABLE ").append(getName(foreignKeyInfoArrayList.get(i).getFkTabname()));
            ddl.append(" ADD ");
            if ("Oracle".equals(foreignKeyInfoArrayList.get(i).getForeignKeyModeFunc())){
                if (!Pattern.matches(patternConstraint,foreignKeyInfoArrayList.get(i).getFkName())){
                    ddl.append("CONSTRAINT ").append(foreignKeyInfoArrayList.get(i).getFkName());
                }
                ddl.append(" FOREIGN KEY(");
            } else {
                ddl.append("CONSTRAINT FOREIGN KEY(");
            }
            // 外键的字段列表
            ddl.append(getKeyCols(connection,
                                    foreignKeyInfoArrayList.get(i).getFkTabid(),
                                    foreignKeyInfoArrayList.get(i).getFkCols(),
                                    tableColumnArrayList
                                ));
            
            ddl.append(") REFERENCES ").append(getName(foreignKeyInfoArrayList.get(i).getPkTabname())).append("(");
            // 参考的主键字段列表
            ddl.append(getKeyCols(connection,
                                    foreignKeyInfoArrayList.get(i).getPkTabid(),
                                    foreignKeyInfoArrayList.get(i).getPkCols(),
                                    tableColumnArrayList
                                ));

            ddl.append(")");
            if ("GBase".equals(foreignKeyInfoArrayList.get(i).getForeignKeyModeFunc())){
                if (!Pattern.matches(patternConstraint,foreignKeyInfoArrayList.get(i).getFkName())){
                    ddl.append(" CONSTRAINT ").append(foreignKeyInfoArrayList.get(i).getFkName());
                }
            }
            ddl.append(";\n");
            // TODO: 禁用约束的语句是否需要区分模式
            if (foreignKeyInfoArrayList.get(i).isIsdisabled()){
                ddl.append("SET CONSTRAINTS ").append(getName(foreignKeyInfoArrayList.get(i).getPkTabname()));
                ddl.append(" DISABLED;\n");
            }
        }
        ddl.append("-- ### FINISH: output foreigen key.\n\n");

        // 8 触发器
        ddl.append("-- ### START: output trigger.\n");
        ArrayList<Trigger> trigArrayList = new ArrayList<>();
        sqlstr = """
            SELECT tri.trigid,tri.trigname,tri.mode,bdy.seqno,bdy.data as trigbody, obj.state
            FROM systriggers tri, systrigbody bdy, sysobjstate obj
            WHERE tri.trigid = bdy.trigid
            AND tri.trigname = obj.name
            AND obj.objtype = 'T'
            AND (   bdy.datakey = CASE WHEN tri.mode = 'O' THEN 'P' ELSE 'A' END 
                 OR bdy.datakey = CASE WHEN tri.mode = 'O' THEN 'P' ELSE 'D' END
            )
            ORDER BY tri.trigid ASC, bdy.datakey DESC,bdy.seqno ASC;    
            """;
        preparedStatement = connection.prepareStatement(sqlstr);
        resultSet = preparedStatement.executeQuery();
        String trigName = null;
        String trigBoday = null;
        String trigMode = null;
        boolean trigDisabled = false;
        int trigId = 0;
        //第一行
        if (resultSet.next()){
            trigId = resultSet.getInt("trigid");
            trigName = resultSet.getString("trigname");
            trigMode = resultSet.getString("trigmode");
            trigBoday = resultSet.getString("trigbody");
            trigDisabled = ("D".equals(resultSet.getString("state")))?true:false;
        }
        while (resultSet.next()){
            if (resultSet.getInt("segno") == 0){
                Trigger trigger = new Trigger(trigName);
                trigger.setTriggerMode(trigMode);
                trigger.setTriggerBody(trigBoday);
                trigger.setIsdisabled(trigDisabled);
                trigArrayList.add(trigger);
                trigId = resultSet.getInt("trigid");
                trigName = resultSet.getString("trigname");
                trigMode = resultSet.getString("trigmode");
                trigBoday = resultSet.getString("trigbody");
                trigDisabled = ("D".equals(resultSet.getString("state")))?true:false;
            } else {
                trigBoday = trigBoday + resultSet.getString("trigbody");
            }
        }
        if (trigId > 0){
            Trigger trigger = new Trigger(trigName);
            trigger.setTriggerMode(trigMode);
            trigger.setTriggerBody(trigBoday);
            trigArrayList.add(trigger);
        }
        for (int i=0;i<trigArrayList.size();i++){
            ddl.append("-- trigger : ").append(trigArrayList.get(i).getName()).append("\n");
            if (displaysqlmode){
                ddl.append("SET ENVIRONMENT SQLMODE '").append(trigArrayList.get(i).getTriggerSqlMode()).append("';\n");
            }
            ddl.append(trigArrayList.get(i).getTriggerBody()).append("\n");
            // Oracle模式也是这么写？？
            if (trigArrayList.get(i).isIsdisabled()){
                ddl.append("SET TRIGGERS ").append(trigArrayList.get(i).getName()).append(" DISABLED;\n");
            }
        }
        ddl.append("-- ### FINISH: output trigger.\n\n");

        // 9 注释，得考虑注释功能对应的数据库版本，commflags。
        ddl.append("-- ### START: output comment.\n");
        int commflags = getCommentFlags(connection);
        if (commflags == 11){   // 含segno
            // 表
            sqlstr = """
                SELECT t.tabname, c.segno, c.comments 
                FROM syscomms c, systables t 
                WHERE c.tabid = t.tabid 
                ORDER BY t.tabid ASC, c.segno ASC;  
                """;
            preparedStatement = connection.prepareStatement(sqlstr);
            resultSet = preparedStatement.executeQuery();
            String tabname = null;
            String tabcomm = null;
            // 第一行
            if (resultSet.next()){
                tabname = resultSet.getString("tabname");
                tabcomm = resultSet.getString("comments");
            }
            while (resultSet.next()){
                if (resultSet.getInt("segno") == 0){
                    ddl.append("COMMENT ON TABLE ").append(tabname).append(" IS '").append(trim(tabcomm).replace("'","''")).append("';\n");
                    tabname = resultSet.getString("tabname");
                    tabcomm = resultSet.getString("comments");
                } else {
                    tabcomm = tabcomm + resultSet.getString("comments");
                }
            }
            if (tabname != null){
                ddl.append("COMMENT ON TABLE ").append(tabname).append(" IS '").append(trim(tabcomm).replace("'","''")).append("';\n");
            }
            // 字段
            sqlstr = """
                SELECT t.tabname,c.colname,cc.segno,cc.comments 
                FROM syscolcomms cc, systables t, syscolumns c 
                WHERE cc.tabid = t.tabid 
                AND cc.tabid = c.tabid 
                AND cc.colno = c.colno 
                ORDER BY t.tabid ASC, c.colno ASC, cc.segno ASC;     
                """;
            preparedStatement = connection.prepareStatement(sqlstr);
            resultSet = preparedStatement.executeQuery();
            tabname = null;
            String colname = null;
            String colcomm = null;
            // 第一行
            if (resultSet.next()){
                tabname = resultSet.getString("tabname");
                colname = resultSet.getString("colname");
                colcomm = resultSet.getString("comments");
            }
            while (resultSet.next()){
                if (resultSet.getInt("segno") == 0){
                    ddl.append("COMMENT ON COLUMN ").append(tabname).append(".").append(colname);
                    ddl.append(" IS '").append(trim(tabcomm).replace("'","''")).append("';\n");
                    tabname = resultSet.getString("tabname");
                    colname = resultSet.getString("colname");
                    colcomm = resultSet.getString("comments");
                } else {
                    colcomm = colcomm + resultSet.getString("comments");
                }
            }
            if (tabname != null){
                ddl.append("COMMENT ON COLUMN ").append(tabname).append(".").append(colname);
                ddl.append(" IS '").append(trim(tabcomm).replace("'","''")).append("';\n");
            }
        } else if (commflags == 1){ // 不含segno
            // 表
            sqlstr = """
                SELECT t.tabname, c.comments 
                FROM syscomms c, systables t 
                WHERE c.tabid = t.tabid 
                ORDER BY t.tabid ASC;     
                """;
            preparedStatement = connection.prepareStatement(sqlstr);
            resultSet = preparedStatement.executeQuery();
            while(resultSet.next()){
                ddl.append("COMMENT ON TABLE ").append(resultSet.getString("tabname")).append(" IS '");
                ddl.append(trim(resultSet.getString("comments")).replace("'","''")).append("';\n");
            }
            // 字段
            sqlstr = """
                SELECT t.tabname,c.colname,cc.comments 
                FROM syscolcomms cc, systables t, syscolumns c 
                WHERE cc.tabid = t.tabid 
                AND cc.tabid = c.tabid 
                AND cc.colno = c.colno 
                ORDER BY t.tabid ASC, c.colno ASC; 
                """;
            preparedStatement = connection.prepareStatement(sqlstr);
            resultSet = preparedStatement.executeQuery();
            while(resultSet.next()){
                ddl.append("COMMENT ON COLUMN ").append(resultSet.getString("tabname")).append(".").append(resultSet.getString("colname"));
                ddl.append(" IS '").append(trim(resultSet.getString("comments")).replace("'","''")).append("';\n");
            }
        }
        ddl.append("-- ### FINISH: output comment.\n\n");

        // 最后变更回原来的激活库
        if (! databasename.equals(preDatabase)){
            setActiveDbname(connection,preDatabase);
        }
        return ddl.toString();
    }

    /**
     * 生成建表语句的extent块信息
     * @param connection
     * @param tableInfo
     * @return
     * @throws SQLException
     */
    private static String buildTableSqlExtent(Connection connection,Table tableInfo) throws SQLException {
        String ddl = "";
        ArrayList<FragmentInfo> tableFragmentInfoArrayList = getTableFragmentInfo(connection,tableInfo.getName());
        // 分片规则，Exprtext 的结果可能不对。
        if (tableFragmentInfoArrayList.size() > 0) {
            ddl = ddl + buildFragmentString(tableFragmentInfoArrayList);
        }

        // 全局临时表级别
        if ("".equals(tableInfo.getTableGlobalTemporary())){
            ddl = ddl + "\n";
        } else {
            ddl = ddl + "\n" + tableInfo.getTableGlobalTemporaryLevel() + " ";
        }
        // 区段大小及锁模式
        ddl = ddl + "EXTENT SIZE " + tableInfo.getFirstExtSize() + " NEXT SIZE " + tableInfo.getNextExtSize();
        ddl = ddl + " LOCK MODE " + tableInfo.getLockType() + ";\n";

        return ddl;
    }

    /**
     * 生成外部表的存储定义部分
     * @param connection
     * @param tableInfo
     * @return
     * @throws SQLException
     */
    private static String buildExtTableSql(Connection connection, Table tableInfo) throws SQLException {
        ExtTableInfo extTableInfo = getExtTableInfo(connection,tableInfo.getName());
        ArrayList<ExtTableDfiles> extTableDfilesArrayList = getExtTableDfiles(connection,tableInfo.getName());
        if (extTableInfo==null){
            return ";";
        }
        String ddl = "\nUSING ( \n  DATAFILES(\n";
        // DATAFILE
        for(int i=0;i<extTableInfo.getNumDfiles();i++){
            ddl = ddl + "    '" + trim(extTableDfilesArrayList.get(i).getDataFile());
            if (extTableDfilesArrayList.get(i).getBlobDir() != null && ! "".equals(trim(extTableDfilesArrayList.get(i).getBlobDir()))){
                ddl = ddl + ";BLOBDIR:" + trim(extTableDfilesArrayList.get(i).getBlobDir());
            }
            if (extTableDfilesArrayList.get(i).getClobDir() != null && ! "".equals(trim(extTableDfilesArrayList.get(i).getClobDir()))){
                ddl = ddl + ";CLOBDIR:" + trim(extTableDfilesArrayList.get(i).getClobDir());
            }
            // 是否最后一行
            if(i==(extTableInfo.getNumDfiles()-1)){
                ddl = ddl + "'\n  ),\n";
            } else {
                ddl = ddl + "',\n";
            }
        }

        // FORMAT
        if ("D".equals(extTableInfo.getFormatType())){
            ddl = ddl + "  FORMAT 'DELIMITED',\n";
        } else if ("F".equals(extTableInfo.getFormatType())){
            ddl = ddl + "  FORMAT 'FIXED',\n";
        } else if ("I".equals(extTableInfo.getFormatType())){
            ddl = ddl + "  FORMAT 'GBASEDBT',\n";           // gbasedbt or informix
        }
        // DELIMITER
        if (extTableInfo.getFieldDelimiter() != null){
            ddl = ddl + "  DELIMITER '" + extTableInfo.getFieldDelimiter() + "',\n";
        }
        // RECORDEND
        if (extTableInfo.getRecordDelimiter() != null){
            ddl = ddl + "  RECORDEND '" + extTableInfo.getRecordDelimiter() + "',\n";
        }
        // DBDATE
        if (extTableInfo.getDateFormat() != null){
            ddl = ddl + "  DBDATE '" + trim(extTableInfo.getDateFormat()) + "',\n";
        }
        // DBMONEY
        if (extTableInfo.getMoneyFormat() != null){
            ddl = ddl + "  DBMONEY '" + trim(extTableInfo.getMoneyFormat()) + "',\n";
        }
        // MAXERRORS
        if (extTableInfo.getMaxErrors() != -1 ){
            ddl = ddl + "  MAXERRORS " + extTableInfo.getMaxErrors() + ",\n";
        }
        // REJECTFILE
        if (extTableInfo.getRejectFile() != null){
            ddl = ddl + "  REJECTFILE '" + trim(extTableInfo.getRejectFile()) + "',\n";
        }
        // flags
        if ((extTableInfo.getFlags() & 4) == 4){
            ddl = ddl + "  DELUXE,\n";
        } else if((extTableInfo.getFlags() & 8) == 8){
            ddl = ddl + "  EXPRESS,\n";
        }
        if ((extTableInfo.getFlags() & 2) == 2){
            ddl = ddl + "  ESCAPE ON\n);";
        } else {
            ddl = ddl + "  ESCAPE OFF\n);";
        }

        return ddl;
    }

    /**
     * 生成建表语句的 create table 字段部分
     * @param connection
     * @param tableInfo
     * @return
     * @throws SQLException
     */
    private static String buildTableSql(Connection connection, Table tableInfo, boolean displaysqlmode) throws SQLException {
        String parttern_constraint = "^[cur]\\d+_\\d+";              // u=unique,r=reference,c=check
        String sqlmode = tableInfo.getTableSqlModeFunc();
        String ddl = "";
        if (displaysqlmode){
            ddl = "SET ENVIRONMENT SQLMODE '" + sqlmode + "';\n";
        }
        ddl = ddl + "CREATE ";
        // global temporary
        if (! "".equals(tableInfo.getTableGlobalTemporary())){
            ddl = ddl + tableInfo.getTableGlobalTemporary() + " ";
        }
        // external
        if("E".equals(tableInfo.getTableTypeCode())){
            ddl = ddl + "EXTERNAL TABLE ";
        } else {
            ddl = ddl + "TABLE ";
        }

        ddl = ddl + getName(tableInfo.getName()) + " (\n";

        ArrayList<CheckInfo> checkInfoArrayList = getCheck(connection,tableInfo.getName());
        ArrayList<ColumnsInfo> columnsInfoArrayList = getColInfo(connection,tableInfo);
        ArrayList<PrimaryKeyInfo> primaryKeyInfoArrayList = getPrimarykey(connection,columnsInfoArrayList,tableInfo.getName());

        // 按顺序处理各个类型
        for (int i=0;i<columnsInfoArrayList.size();i++){
            // 字段名
            ddl = ddl + "  " + getName(columnsInfoArrayList.get(i).getColName());
            // 字段类型
            ddl = ddl + " " + getColTypeName(columnsInfoArrayList.get(i).getColType(),
                columnsInfoArrayList.get(i).getColLength());
            // 是否为 NOT NULL
            if (! columnsInfoArrayList.get(i).isIsNullable()){
                ddl = ddl + " NOT NULL";
            }
            // 默认值，依据ColDefType处理
            if (columnsInfoArrayList.get(i).getColDefType() != null){
                ddl = ddl + " DEFAULT " + getDefaults(columnsInfoArrayList.get(i).getColType(),
                    columnsInfoArrayList.get(i).getColDefType(),
                    columnsInfoArrayList.get(i).getColDef());
            }
            // 非最后一个字段，后面加上 ,\n
            if (i<columnsInfoArrayList.size()-1){
                ddl = ddl + ",\n";
            }
        }
        // 检查约束
        if (checkInfoArrayList.size() > 0){
            for(int i=0;i<checkInfoArrayList.size();i++){
                // Oracle模式下，constraint在前
                if ("Oracle".equalsIgnoreCase(sqlmode)){
                    if (!Pattern.matches(parttern_constraint, checkInfoArrayList.get(i).getConstrName())) {
                        ddl = ddl + ",\n  CONSTRAINT " + getName(checkInfoArrayList.get(i).getConstrName());
                        ddl = ddl + "  CHECK " + checkInfoArrayList.get(i).getCheckText();
                    } else {
                        ddl = ddl + ",\n  CHECK " + checkInfoArrayList.get(i).getCheckText();
                    }
                    // default GBase模式
                } else {
                    ddl = ddl + ",\n  CHECK " + checkInfoArrayList.get(i).getCheckText();
                    if (!Pattern.matches(parttern_constraint, checkInfoArrayList.get(i).getConstrName())) {
                        ddl = ddl + " CONSTRAINT " + getName(checkInfoArrayList.get(i).getConstrName());
                    }
                }
            }
        }
        // 主键约束及唯一约束
        if (primaryKeyInfoArrayList.size() > 0){
            for(int i=0;i<primaryKeyInfoArrayList.size();i++){
                // Oracle模式
                if ("Oracle".equalsIgnoreCase(sqlmode)){
                    if (!Pattern.matches(parttern_constraint, primaryKeyInfoArrayList.get(i).getConstrName())) {
                        ddl = ddl + ",\n  CONSTRAINT " + getName(primaryKeyInfoArrayList.get(i).getConstrName());
                        if ("P".equals(primaryKeyInfoArrayList.get(i).getConstrType())) {
                            ddl = ddl + "  PRIMARY KEY(";
                        } else if ("U".equals(primaryKeyInfoArrayList.get(i).getConstrType())) {
                            ddl = ddl + "  UNIQUE(";
                        }
                        ddl = ddl + primaryKeyInfoArrayList.get(i).getIdxCols() + ")";
                    } else {
                        if ("P".equals(primaryKeyInfoArrayList.get(i).getConstrType())) {
                            ddl = ddl + ",\n  PRIMARY KEY(";
                        } else if ("U".equals(primaryKeyInfoArrayList.get(i).getConstrType())) {
                            ddl = ddl + ",\n  UNIQUE(";
                        }
                        ddl = ddl + primaryKeyInfoArrayList.get(i).getIdxCols() + ")";
                    }
                    // default GBase模式
                } else {
                    if ("P".equals(primaryKeyInfoArrayList.get(i).getIdxCols())) {
                        ddl = ddl + ",\n  PRIMARY KEY(";
                    } else if ("U".equals(primaryKeyInfoArrayList.get(i).getConstrType())) {
                        ddl = ddl + ",\n  UNIQUE(";
                    }
                    ddl = ddl + primaryKeyInfoArrayList.get(i).getIdxCols() + ")";
                    if (!Pattern.matches(parttern_constraint, primaryKeyInfoArrayList.get(i).getConstrName())) {
                        ddl = ddl + "  CONSTRAINT " + getName(primaryKeyInfoArrayList.get(i).getConstrName());
                    }
                }
            }
        }
        ddl = ddl + "\n) ";
        return ddl;
    }

    /**
     * 获取主键索引信息
     * @param connection
     * @param arrayList
     * @param tablename
     * @param delimident
     * @return
     * @throws SQLException
     */
    private static ArrayList<PrimaryKeyInfo> getPrimarykey(Connection connection,ArrayList<ColumnsInfo> arrayList,String tablename) throws SQLException {
        ArrayList<PrimaryKeyInfo> primaryKeyArrayList = new ArrayList<>();
        String sqlstr = "SELECT con.constrname,con.constrtype,idx.indexkeys::lvarchar as idxcols " +
                "FROM sysconstraints con, sysindices idx, systables t " +
                "WHERE con.idxname = idx.idxname " +
                "AND con.tabid = t.tabid " +
                "AND t.tabname = ? " +
                "AND con.constrtype in ('P','U')";
        PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
        preparedStatement.setString(1,tablename);
        ResultSet resultSet = preparedStatement.executeQuery();

        while (resultSet.next()){
            PrimaryKeyInfo primaryKeyInfo = new PrimaryKeyInfo();
            primaryKeyInfo.setIdxCols(getIdxCols(connection, resultSet.getString("idxcols"),getColNameListByColumnsInfo(arrayList)));
            primaryKeyInfo.setConstrName(resultSet.getString("constrname"));
            primaryKeyInfo.setConstrType(resultSet.getString("constrtype"));
            primaryKeyArrayList.add(primaryKeyInfo);
        }

        if(resultSet != null) resultSet.close();
        if(preparedStatement != null) preparedStatement.close();
        return primaryKeyArrayList;
    }

    //added by L3 20260124
    public static String printPackage(Connection connection, String procname) throws SQLException {
        StringBuilder packageString = new StringBuilder();
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);

        //data字段是nchar(256),可能存在空白区域没有空格填充，导致查询出来的数据无法正常解析，使用substr截取目前看没有问题
        //preparedStatement = connection.prepareStatement("select b.procid,substr(b.data,0,length(b.data)) from sysprocedures p, sysprocbody b where p.procid = b.procid and p.mode='O' and retsize=0 and datakey='T' and p.procname=? order by b.procid,b.seqno");
        List<String[]> rows = runner.query(
                """
                select b.procid,b.data
                from sysprocedures p, sysprocbody b
                where p.procid = b.procid
                and p.mode='O'
                and retsize=0
                and datakey='T'
                and p.procname=?
                order by b.procid,b.seqno
                """,
                List.of(procname),
                rs -> new String[]{String.valueOf(rs.getInt(1)), rs.getString(2)}
        );
        int lastProcId = 0;
        int prevProcId = 0;
        for (String[] row : rows){
            lastProcId = Integer.parseInt(row[0]);
            if (prevProcId == 0) {
                prevProcId = lastProcId;
            }
            if (prevProcId != lastProcId) {  //包头与包体之间增加执行
                packageString.append("\n/\n");
            }
            packageString.append(row[1].replaceAll("\u0000", ""));
            prevProcId = lastProcId;
        }
        packageString.append("\n/\n");

        return packageString.toString();
    }

}
