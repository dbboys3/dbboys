package com.dbboys.impl.dialect.gbase;

import com.dbboys.api.DdlRepository;
import com.dbboys.api.DdlRepository.DatabaseDdlParts;

import java.lang.reflect.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
import com.dbboys.vo.TableWithColumn;
import com.dbboys.vo.Trigger;
import com.dbboys.vo.View;

import javafx.scene.control.Tab;

import com.dbboys.db.SqlRunner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.formula.functions.T;

public final class GbaseDdlRepository implements DdlRepository {
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;
    private static final Logger log = LogManager.getLogger(GbaseDdlRepository.class);

    /**
     * 获取当前的数据库
     * @param connection
     * @return
     * @throws SQLException
     */
    private static String getActiveDbname(Connection connection) throws SQLException {
        String sqlstr = "select dbinfo('dbname') as dbname from dual";
        String currdatabase = "sysmaster";
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()){
                currdatabase = resultSet.getString("dbname").trim();
            }
        }
        return currdatabase;
    }

    /**
     * 设置当前数据库名
     * @param connection
     * @param database
     */
    private static void setActiveDbname(Connection connection, String database) throws SQLException {
        String sqlstr = "database " + database;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr)) {
            preparedStatement.executeUpdate();
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
        String sqlstr = "select dbinfo('version_gbase','minor') as version from dual";
        // AEE_3.5.1_3X2_8_25d861
        String pattern = "\\d+\\.\\d+\\.\\d+";
        Pattern r = Pattern.compile(pattern);
        String tmpversion = "TL_3.1.0_1";
        int version = 30100;                    // version 3.2.0

        // version_gbase 在 3.2.x及之后的版本中支持；
        // 3.5.1及之后支持sqlmode写法
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()) {
                tmpversion = trim(resultSet.getString("version"));
            }
        } catch (SQLException e){   // 没有version_gbase, 使用默认的tmpversion
            // 不做任何事
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
        String sqlstr = "select dbinfo('version_gbase','full') as version from dual";

        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()){
                version = trim(resultSet.getString("version"));
            }
        } catch (SQLException e) {      // 版本低于3.2.x
            sqlstr = "select dbinfo('version','full') as version from dual";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
                 ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()){
                    version = trim(resultSet.getString("version"));
                }
            } catch (SQLException e1) {
                // 不做任何事情
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
        return getName(str,"GBase");
    }

    /**
     * 根据SQL模式、参数判断是否需要加反引号或者双引号
     * @param str
     * @param sqlmode
     * @return
     */
    private static String getName(String str, String sqlmode){
        if ("MySQL".equals(sqlmode)){
            return "`" + str + "`";
        } else {
            String patternDelimIdent = "^[a-z_][a-z0-9_]*$";
            if(Pattern.matches(patternDelimIdent,str)){
                return str;
            } else {
                return "\"" + str + "\"";
            }
        }
    }

    /**
     * 对于多数据类型，可使用此方法
     * 16384 ： Oracle，65536 ： MySQL, 0 : GBase 
     * @param flags
     * @return
     */
    @Deprecated
    private static String getSqlModeFunc(int flags){
        if ((flags % 16384) == 16384){
            return "Oracle";
        } else if ((flags % 65536) == 65536){
            return "MySQL";
        }
        return "GBase";
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
        return getLength(coltype,collength,30000);
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
        switch (coltype) {
            case "TINYINT":
                mycollen = 3;
                break;
            case "SMALLINT":
            case "BOOLEAN":
                mycollen = 5;
                break;
            case "MEDIUMINT":
            	mycollen = 8;
            	break;
            case "INTEGER":
            case "SERIAL":
            case "DATE":
                mycollen = 10;
                break;
            case "INT8":
            case "SERIAL8":
            case "BIGINT":
            case "BIGSERIAL":
            case "BIGINT UNSIGNED":
                mycollen = 19;
                break;
            case "FLOAT":
                mycollen = 17;
                break;
            case "SMALLFLOAT":
                mycollen = 7;
                break;
            case "DECIMAL":
            case "MONEY":
                mycollen = collength / 256;
                break;
            case "TEXT":
            case "BYTE":
            case "BLOB":
            case "CLOB":
                mycollen = 2147483647;
                break;
            case "VARCHAR":
            case "NVARCHAR":
            case "VARCHAR2":
            case "NVARCHAR2":
                // collength = (min_space * 256) + max_size （对于2.0及之前），collength = (min_space * 65536) + max_size（3.0及之后）
                if (dbver > 30000) {
                    if (collength > 0) {
                        mycollen = collength % 65536;
                    } else {
                        mycollen = Long.valueOf((collength + 4294967296L) % 65536).intValue();
                    }
                } else {
                    if (collength > 0) {
                        mycollen = collength % 256;
                    } else {
                        mycollen = (collength + 65536) % 256;
                    }
                }
                break;
            default:
                break;
        }
        // datetime 类型特殊处理
        if(coltype.startsWith("DATETIME") || coltype.startsWith("TIMESTAMP")){ 
            mycollen = getDTLength(collength); 
        } else if (coltype.startsWith("TIMESTAMP")){
            mycollen = 26 ;
        }
        return mycollen;
    }

    /**
     * 返回数据类型名称。除40、41需要使用扩展类型外，其它都可通过coltype,collength计算得到。默认sqlmode为GBase。
     * @param coltype
     * @param collength
     * @param extended_id
     * @param df
     * @param extypename
     * @return
     */
    private static String getColTypeName(int coltype, int collength, int extended_id, int df, String extypename){
        return getColTypeName(coltype, collength, extended_id, df, extypename, "GBase");
    }

    /**
     * 返回数据类型名称。除40、41需要使用扩展类型外，其它都可通过coltype,collength计算得到。
     * @param coltype
     * @param collength
     * @param extended_id
     * @param df
     * @param extypename
     * @param sqlmode
     * @return
     */
    private static String getColTypeName(int coltype, int collength, int extended_id, int df, String extypename, String sqlmode){
        String coltypeName = null;
        int mycoltype = coltype % 256;
        switch (mycoltype) {
            case 0:
                coltypeName = "CHAR";               break;
            case 1:
                coltypeName = "SMALLINT";           break;
            case 2:
                coltypeName = "INTEGER";            break;
            case 3:
                coltypeName = "FLOAT";              break;
            case 4: 
                coltypeName = "SMALLFLOAT";         break;
            case 5:
                coltypeName = "DECIMAL";            break;
            case 6:
                coltypeName = "SERIAL";             break;
            case 7:
                coltypeName = "DATE";               break;
            case 8:
                coltypeName = "MONEY";              break;
            case 9:
                coltypeName = "NULL";               break;
            case 10:
                coltypeName = "DATETIME" + getDTColTypeName(collength);
                if ("MySQL".equals(sqlmode) && "DATETIME YEAR TO SECOND".equals(coltypeName)){
                    coltypeName = "DATETIME";
                }
                break;
            case 11:
                coltypeName = "BYTE";               break;
            case 12:
                if ("MySQL".equals(sqlmode)){
                    coltypeName = "LONGTEXT";
                } else {
                    coltypeName = "TEXT";
                }
                break;
            case 13:    
                coltypeName = "VARCHAR";            break;
            case 14:
                coltypeName = "INTERVAL" + getDTColTypeName(collength); break;
            case 15:
                coltypeName = "NCHAR";              break;
            case 16:
                coltypeName = "NVARCHAR";           break;
            case 17:
                coltypeName = "INT8";               break;
            case 18:
                coltypeName = "SERIAL8";            break;
            case 19:
                coltypeName = "SET(LVARCHAR)";      break;
            case 20:
                coltypeName = "MULTISET(SENDRECEIVE)"; break;
            case 21:
                coltypeName = "LIST";               break;
            case 22:
                coltypeName = "ROW";                break;
            case 23:
                coltypeName = "COLLECTION";         break;
            case 24:
                coltypeName = "ROWREF";             break;
            case 40:
            case 41:
                coltypeName = extypename.toUpperCase(); break;
            case 42:
                coltypeName = "REFSERIAL8";         break;
            case 52:
                coltypeName = "BIGINT";             break;
            case 53:
                coltypeName = "BIGSERIAL";          break;
            case 63:
                coltypeName = "VARCHAR2";           break;
            case 64:
                coltypeName = "NVARCHAR2";          break;
            case 65:
                coltypeName = "TIMESTAMP WITH TIME ZONE"; break;
            case 66:
                coltypeName = "BIGINT UNSIGNED";    break;
            case 67:
                coltypeName = "TINYINT";            break;
            case 68:
                coltypeName = "MEDIUMINT";          break;
            case 69:
                coltypeName = "BIT";                break;
            default:
                break;
        }
        return coltypeName;
    }
    
    /**
     * 获取注释标识，1为有syscomms表，10为表syscomms表有seqno字段（用于排序）
     * @param connection
     * @return
     * @throws SQLException
     */
    private static int getCommentFlags(Connection connection) throws SQLException {
        int commflags = 0;
        String sqlstr = """
                select sum(comm) as commflags  
                from ( 
                    select 1 as comm from systables t where t.tabname = 'syscomms' 
                    union all 
                    select 10 as comm from syscolumns c,systables t where c.tabid = t.tabid and t.tabname = 'syscomms' and c.colname = 'segno' 
                );
                """;     
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
            ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()){
                commflags = resultSet.getInt("commflags");
            }
        }
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
        int dbVersion = 30000; // 默认数据库JDBC版本
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
            rowTableInfo.setTableSqlMode(resultSet.getInt("tableflags"));
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

        dbVersion=getDataBaseProductVersionNumber(connection);
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
        // 去除隐藏列
        /*  SQL语句中不要以下两行，可以直接依据程序中计算得到（同时解决p,s允许空的问题（不赋值））
         *        ,CASE WHEN mod(sc.coltype,256) in (1,2,52,17,6,18,53,5,8) THEN 10 WHEN mod(sc.coltype,256) in (3,4) THEN 2 ELSE 0 END as typep
         *        ,CASE WHEN mod(sc.coltype,256) in (5,8) THEN MOD(sc.collength,256) ELSE 0 END as types
         */
        String sql = """
                SELECT
                   sc.colno colno
                  ,sc.colname colname
                  ,sc.coltype,sc.collength
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
                LEFT JOIN syscolumns sc ON t.tabid = sc.tabid and bitand(sc.colattr,1) = 0
                LEFT JOIN sysdefaults df ON (t.tabid = df.tabid AND sc.colno = df.colno)
                LEFT JOIN sysdefaultsexpr de ON (t.tabid = de.tabid AND sc.colno = de.colno and de.type='T')
                LEFT JOIN sysxtdtypes sx ON (sx.type in (sc.coltype,mod(sc.coltype,256)) AND sx.extended_id = sc.extended_id)
                WHERE t.tabname = ?
                ORDER BY sc.colno;
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        List<ColumnsInfo> rows = runner.query(sql, List.of(tableInfo.getName()), resultSet -> {
            ColumnsInfo columnsInfo = new ColumnsInfo();
            columnsInfo.setColNo(resultSet.getInt("colno"));
            columnsInfo.setColName(resultSet.getString("colname"));
            String coltypename = getColTypeName(resultSet.getInt("coltype"), 
                                                resultSet.getInt("collength"), 0, 0, 
                                                resultSet.getString("sxname"),
                                                tableInfo.getTableSqlMode()
                                            );
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
                arrayList.add(columnsInfo);
            }
        }
        // 增加注释信息
        if (columnsCommInfos != null) {
            for (ColumnsCommInfo columnsCommInfo : columnsCommInfos) {
                if (arrayList.stream().anyMatch(colInfo -> colInfo.getColName().equals(columnsCommInfo.getColName()))) {
                    arrayList.stream()
                            .filter(colInfo -> colInfo.getColName().equals(columnsCommInfo.getColName()))
                            .findFirst()
                            .ifPresent(colInfo -> colInfo.setColComm(columnsCommInfo.getColComm()));
                }
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
     * 获取数据类型名称，默认数据库版本为30000
     * @param coltype
     * @param collength
     * @return
     */
    private static String getColTypeName(String coltype, int collength){
        return getColTypeName(coltype, collength, 30000);
    }

    /**
     * 有精度和标度的数据类型需要处理
     * @param coltype
     * @param collength
     * @return
     */
    private static String getColTypeName(String coltype, int collength, int dbversion){
        String coltypename = coltype;
        switch (coltype) {
            case "VARCHAR":
            case "NVARCHAR":
            case "VARCHAR2":
            case "NVARCHAR2":
                 // 有最小字段长度，需处理，是否需要考虑32K？
                if (collength/65536 > 0){
                    coltypename = coltypename + "(" + collength + "," + collength/65536 + ")";
                } else {
                    coltypename = coltypename + "(" + collength + ")";
                }
                break;
            case "DECIMAL":
            case "MONEY":
                if (collength%256 == 255){
                    coltypename = coltypename + "(" + collength/256 + ")";
                } else {
                    coltypename = coltypename + "(" + collength/256 + "," + collength%256 + ")";
                }
                break;
            case "LVARCHAR":
            case "CHAR":
            case "NCHAR":
            case "RAW":
                coltypename = coltypename + "(" + collength + ")";
                break;
            case "BIT":
                coltypename = coltypename + "(" + collength/256 + ")";
                break;
            default:
                break;
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
    private static ArrayList<PrimaryKeyInfo> getPrimaryKey(Connection connection, ArrayList<ColumnsInfo> columns, String tablename, String sqlmode) throws SQLException {
        // 去除虚拟字段产生的约束，增加mysqlmode下K（key）,Q（unique key）的约束类型
        String sql = """
                SELECT con.constrname,con.constrtype,cast(idx.indexkeys as lvarchar) as idxcols
                FROM sysconstraints con, sysindices idx, systables t
                WHERE con.idxname = idx.idxname
                AND con.tabid = t.tabid
                AND t.tabname = ?
                AND con.constrtype in ('P','U','K','Q')
                AND LEFT(con.constrname,1) != ' '
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return new ArrayList<>(runner.query(sql, List.of(tablename), resultSet -> {
            PrimaryKeyInfo primaryKeyInfo = new PrimaryKeyInfo();
            primaryKeyInfo.setIdxCols(getIdxCols(connection, resultSet.getString("idxcols"), getColNameListByColumnsInfo(columns),sqlmode));
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
    private static ArrayList<Index> getIndexesInfo(Connection connection, ArrayList<ColumnsInfo> columns, String tablename, String sqlmode) throws SQLException {
        // 去除虚拟字段产生的索引和约束、主键字段索引
        String sql = """
                SELECT idx.idxname, idx.owner as idxowner, idx.idxtype, idx.clustered as idxcluster, idx.indexkeys::lvarchar as idxcols, t.flags
                FROM sysindices idx, systables t
                WHERE idx.tabid = t.tabid
                AND t.tabname = ?
                AND NOT EXISTS (SELECT 1 FROM sysconstraints c WHERE c.idxname = idx.idxname AND LEFT(c.constrname,1) = ' ') 
                AND LEFT(idx.idxname,1) != ' '
                """;
        SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
        return new ArrayList<>(runner.query(sql, List.of(tablename), resultSet -> {
            Index indexInfo = new Index(resultSet.getString("idxname"));
            indexInfo.setIndexOwner(trim(resultSet.getString("idxowner")));
            indexInfo.setIndexType(resultSet.getString("idxtype"));
            indexInfo.setIndexCluster(resultSet.getString("idxcluster"));
            indexInfo.setIndexCols(getIdxCols(connection, resultSet.getString("idxcols"), getColNameListByColumnsInfo(columns),sqlmode));
            indexInfo.setTableSqlMode(resultSet.getInt("flags"));
            indexInfo.setTableName(tablename);
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
            switch (coltype) {
                case "SMALLINT":
                case "INTEGER":
                case "SERIAL": 
                case "SERIAL8":
                case "INT8":
                case "BIGSERIAL":
                case "BIGINT":
                case "FLOAT":
                case "SMALLFLOAT":
                case "MONEY":
                case "DECIMAL":
                case "BIT":
                case "TINYINT":
                case "MEDIUMINT":
                case "BIGINT UNSIGNED":
                    strdef = coldef;
                    break;           
                default:
                    if (coldef != null) {
                        strdef = "\'" + coldef.replace("'", "''") + "\'";
                    } else {
                        strdef = "NULL";
                    }
                    break;
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
            foreignKeyInfo.setForeignKeySqlMode(resultSet.getInt("flags"));
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
    @Override
    public String printTable(Connection connection,String tablename) throws SQLException, ClassNotFoundException {
        String patternConstraint = "^[cur]\\d+_\\d+";              // u=unique,r=reference,c=check
        Table tableInfo = getTableInfo(connection,tablename);
        String sqlmode = tableInfo.getTableSqlMode();
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
        ddl.append(tableInfo.getTableGlobalTemporary());
        // external && raw 
        ddl.append(tableInfo.getTableFlag()).append("TABLE ");
        // OWNER
        if (tableInfo.getTableOwner()==null){
            return "";
        }
        ddl.append(getName(tableInfo.getName(),sqlmode)).append(" (\n");

        ArrayList<CheckInfo> checks = getCheck(connection,tablename);
        ArrayList<ColumnsInfo> columns = getColInfo(connection,tableInfo);
        ArrayList<PrimaryKeyInfo> primaryKeys = getPrimaryKey(connection,columns,tablename,sqlmode);
        ArrayList<Index> indexes = getIndexesInfo(connection,columns,tablename,sqlmode);
        ArrayList<FragmentInfo> tableFragments = getTableFragmentInfo(connection,tablename);
        ArrayList<String> triggers = getTriggerList(connection,tablename);
        ArrayList<ForeignKeyInfo> foreignKeys = getForeignKeyInfo(connection,tablename);

        appendColumnsDefinition(ddl, columns, sqlmode);
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
    private static void appendColumnsDefinition(StringBuilder ddl, ArrayList<ColumnsInfo> columns, String sqlmode) {
        for (int i = 0; i < columns.size(); i++) {
            ColumnsInfo column = columns.get(i);
            ddl.append("  ").append(getName(column.getColName(),sqlmode));
            ddl.append(" ").append(getColTypeName(
                    column.getColType(),
                    column.getColLength()
            ));
            // 修改为先 default, 再not null
            if (column.getColDefType() != null) {
                ddl.append(" DEFAULT ").append(getDefaults(
                        column.getColType(),
                        column.getColDefType(),
                        column.getColDef()
                ));
            }
            // 非空约束
            if (!column.isIsNullable()) {
                ddl.append(" NOT NULL");
            }
            // mysql模式下，comment在字段后面
            if ("MySQL".equals(sqlmode)){
                if (column.getColComm() != null){
                    ddl.append(" COMMENT '").append(column.getColComm()).append("'");
                }
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
                    ddl.append(",\n  CONSTRAINT ").append(getName(check.getConstrName(),sqlmode));
                    ddl.append("  CHECK ").append(check.getCheckText());
                } else {
                    ddl.append(",\n  CHECK ").append(check.getCheckText());
                }
            } else {
                ddl.append(",\n  CHECK ").append(check.getCheckText());
                if (!Pattern.matches(patternConstraint, check.getConstrName())) {
                    ddl.append(" CONSTRAINT ").append(getName(check.getConstrName(),sqlmode));
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
                    ddl.append(",\n  CONSTRAINT ").append(getName(primaryKey.getConstrName(),sqlmode));
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
            } else if ("MySQL".equals(sqlmode)) {                   // mysql模式下，主键显示为P，唯一索引显示为K，普通索引显示为Q
                if ("P".equals(primaryKey.getConstrType())) {
                    ddl.append(",\n  PRIMARY KEY");
                } else if ("K".equals(primaryKey.getConstrType())) {
                    ddl.append(",\n  KEY ").append(getName(getIndexNameBySqlMode(primaryKey.getConstrName(),sqlmode),sqlmode));
                } else if ("Q".equals(primaryKey.getConstrType())) {
                    ddl.append(",\n  UNIQUE KEY ").append(getName(getIndexNameBySqlMode(primaryKey.getConstrName(),sqlmode),sqlmode));
                }
                ddl.append(" (").append(primaryKey.getIdxCols()).append(")");
            } else {
                if ("P".equals(primaryKey.getConstrType())) {
                    ddl.append(",\n  PRIMARY KEY(");
                } else if ("U".equals(primaryKey.getConstrType())) {
                    ddl.append(",\n  UNIQUE(");
                }
                ddl.append(primaryKey.getIdxCols()).append(")");
                if (!Pattern.matches(patternConstraint, primaryKey.getConstrName())) {
                    ddl.append("  CONSTRAINT ").append(getName(primaryKey.getConstrName(),sqlmode));
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
     * 仅用于获取索引名称，mysql模式下索引名称为 表名$$索引名
     * @param indexname
     * @param sqlmode
     * @return
     */
    private static String getIndexNameBySqlMode(String indexname, String sqlmode){
        if ("MySQL".equals(sqlmode)){
            String[] tmpstr = indexname.split("\\$\\$");
            if (tmpstr.length > 1){
                return tmpstr[1];
            } else {
                return tmpstr[0];
            }
        } else {
            return indexname;
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
        // 增加mysql模式下表的comment
        if ("MySQL".equals(sqlmode) && tableInfo.getTableComm() != null){
            ddl.append("COMMENT '").append(tableInfo.getTableComm()).append("' ");
        }
        ddl.append("EXTENT SIZE ").append(tableInfo.getFirstExtSize()).append(" NEXT SIZE ").append(tableInfo.getNextExtSize());
        if ("MySQL".equals(sqlmode)){
            // mysql模式下暂时不支持 lock mode row的写法
            ddl.append(";\n");
        } else {
            ddl.append(" LOCK MODE ").append(tableInfo.getLockTypeFunc()).append(";\n");
        }

        for (Index index : indexes) {
            ddl.append("\nCREATE");
            if ("U".equals(index.getIndexType())) {
                ddl.append(" UNIQUE INDEX");
            } else if ("C".equals(index.getIndexCluster())) {
                ddl.append(" CLUSTER INDEX");
            } else {
                ddl.append(" INDEX");
            }
            // 不再打印表owner及索引owner
            ddl.append(" ").append(getName(getIndexNameBySqlMode(index.getName(), sqlmode),sqlmode)).append(" ON ");
            ddl.append(getName(tableInfo.getName(),sqlmode)).append("(").append(index.getIndexCols()).append(")");
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
                    ddl.append("CONSTRAINT ").append(getName(foreignKey.getFkName(),sqlmode));
                }
                ddl.append(" FOREIGN KEY(");
            } else {
                if(displaySqlMode(getDataBaseProductVersionNumber(connection))){
                    ddl.append("SET ENVIRONMENT SQLMODE 'GBase';\n");
                }
                ddl.append("ALTER TABLE ").append(getName(foreignKey.getFkTabname(),sqlmode));
                ddl.append(" ADD CONSTRAINT FOREIGN KEY(");
            }
            ddl.append(getIdxCols(foreignKey.getFkCols(), fkColumns,sqlmode)).append(") ");

            ArrayList<String> pkColumns = getColNameListByTablename(connection, foreignKey.getPkTabname());
            ddl.append("REFERENCES ").append(getName(foreignKey.getPkTabname(),sqlmode));
            ddl.append("(").append(getIdxCols(foreignKey.getPkCols(), pkColumns,sqlmode)).append(")");

            if ("GBase".equals(foreignKey.getForeignKeyModeFunc())) {
                if (!Pattern.matches(patternConstraint, foreignKey.getFkName())) {
                    ddl.append(" CONSTRAINT ").append(getName(foreignKey.getFkName(),sqlmode));
                }
            }
            ddl.append(";\n");

            // TODO: Oracle模式也是这么写？？
            if (foreignKey.isIsdisabled()){
                ddl.append("SET CONSTRAINTS ").append(getName(foreignKey.getFkName(),sqlmode)).append(" DISABLED;\n");
            }
        }

        if (tableInfo.getTableComm() != null) {
            // mysql创建comment的语法不同
            if ("Oracle".equals(sqlmode) || "GBase".equals(sqlmode) ){
                ddl.append("\nCOMMENT ON TABLE ").append(getName(tablename,sqlmode)).append(" IS '")
                    .append(tableInfo.getTableComm()).append("';");
            }
        }
        for (ColumnsInfo column : columns) {
            if (column.getColComm() != null) {
                if ("Oracle".equals(sqlmode) || "GBase".equals(sqlmode) ){
                    ddl.append("\nCOMMENT ON COLUMN ").append(getName(tablename,sqlmode)).append(".")
                        .append(getName(column.getColName(),sqlmode)).append(" IS '")
                        .append(column.getColComm()).append("';");
                }
            }
        }

        ddl.append("\n");
        for (String triggerName : triggers) {
            ddl.append("\n").append(new GbaseDdlRepository().printTrigger(connection, triggerName));
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
        StringBuilder ddl = new StringBuilder();
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
            ddl.append(" IN ").append(arrayList.get(0).getDbspace());
        } else if ("T".equals(fragtype)){               // 索引使用表的分片表达式

        } else if ("R".equals(fragtype)){
            ddl.append(" \nFRAGMENT BY ROUND ROBIN \n");
            for(int i=0;i<arrayList.size();i++){
                ddl.append("PARTITION ").append(arrayList.get(i).getPartition()).append(" IN ").append(arrayList.get(i).getDbspace());
                if (i<arrayList.size()-1){
                    ddl.append(",\n");
                }
            }
        } else if ("E".equals(fragtype)){
            ddl.append(" \nFRAGMENT BY EXPRESSION \n");
            for(int i=0;i<arrayList.size();i++){
                ddl.append("PARTITION ").append(arrayList.get(i).getPartition()).append(" ").append(arrayList.get(i).getExprtext());
                ddl.append(" IN ").append(arrayList.get(i).getDbspace());
                if (i<arrayList.size()-1){
                    ddl.append(",\n");
                }
            }
        } else if ("L".equals(fragtype)){
            ddl.append(" \nFRAGMENT BY LIST(").append(fragcolumn).append(") \n");
            for(int i=0;i<arrayList.size();i++){
                if (arrayList.get(i).getEvalpos() < 0){
                    continue;
                }
                ddl.append("PARTITION ").append(arrayList.get(i).getPartition()).append(" ").append(arrayList.get(i).getExprtext());
                ddl.append(" IN ").append(arrayList.get(i).getDbspace());
                if (i<arrayList.size()-1){
                    ddl.append(",\n");
                }
            }
        } else if ("N".equals(fragtype)){
            ddl.append(" \nFRAGMENT BY RANGE(").append(fragcolumn).append(") INTERVAL(").append(fraginterval).append(") \n");
            if (numfragments > 0){
                ddl.append("ROLLING (").append(numfragments).append(" FRAGMENTS) ").append(fragdetech).append(" \n");
            }
            ddl.append("STORE IN (").append(fragdbslist).append(") \n");
            for(int i=0;i<arrayList.size();i++){
                if (arrayList.get(i).getEvalpos() < 0){
                    continue;
                }
                ddl.append("PARTITION ").append(arrayList.get(i).getPartition()).append(" ").append(arrayList.get(i).getExprtext());
                ddl.append(" IN ").append(arrayList.get(i).getDbspace());
                if (i<arrayList.size()-1){
                    ddl.append(",\n");
                }
            }
        }
        return ddl.toString();
    }

    /**
     * 索引列表转字段信息
     * @param idxColsString
     * @param arrayList
     * @return
     */
    private static String getIdxCols(Connection connection,String idxColsString,ArrayList<String> colnameList, String sqlmode) throws SQLException {
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
                idxcols = idxcols + getName(funcname,sqlmode) + "(";
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
                idxcols = idxcols + getName(colnameList.get(Math.abs(colno) - 1),sqlmode);
                if ("".equals(funcparam)){
                    idxcols = idxcols + ")";
                } else {
                    idxcols = idxcols + "," + funcparam + ")";
                }
                idxcols = idxcols + sortby;
            } else {                                // 普通字段 -3 [1]
                int colno = Integer.valueOf(tmpstr.substring(0,tmpstr.indexOf(" ")));
                sortby = (colno<0)?" DESC,":",";
                idxcols = idxcols + getName(colnameList.get(Math.abs(colno) - 1),sqlmode) + sortby;
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
    private static String getIdxCols(String idxColsString,ArrayList<String> colnameList, String sqlmode) {
        String idxcols = "";
        String sortby = ",";
        for (String cols : idxColsString.trim().split(",(?![^()]*+\\))")){
            String tmpstr = cols.trim();
            // 普通字段 -3 [1]
            int colno = Integer.valueOf(tmpstr.substring(0,tmpstr.indexOf(" ")));
            sortby = (colno<0)?" DESC,":",";
            idxcols = idxcols + getName(colnameList.get(Math.abs(colno) - 1),sqlmode) + sortby;
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
    private static String getKeyCols(Connection connection, int tableId, String keyCols, ArrayList<TableWithColumn> tableColumnArrayList, String sqlmode) throws SQLException {
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
                keyList.append(getName(funcname,sqlmode)).append("(");
                // 获取字段 及 排序方式 及多参函数参数
                String tmpstr_func = tmpstr.substring(tmpstr.indexOf("(")+1,tmpstr.indexOf(")"));
                int colno = 0;
                if (tmpstr_func.indexOf(",") > 0){  // 多值参数，有其它参数
                    colno = Integer.valueOf(tmpstr_func.substring(0,tmpstr_func.indexOf(",")));
                    funcparam = tmpstr_func.substring(tmpstr_func.indexOf(",")+1).trim();
                } else {                                // 单值
                    colno = Integer.valueOf(tmpstr_func);
                }
                for (TableWithColumn tableColumn : tableColumnArrayList){
                    if (tableId == tableColumn.getTableId() && Math.abs(colno) == tableColumn.getColumnNo()){
                        keyList.append(getName(tableColumn.getColumnName(),sqlmode));
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
                for (TableWithColumn tableColumn : tableColumnArrayList){
                    if (tableId == tableColumn.getTableId() && Math.abs(colno) == tableColumn.getColumnNo()){
                        keyList.append(getName(tableColumn.getColumnName(),sqlmode));
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
                SELECT i.owner idxowner,i.idxname,t.owner as tabowner, t.tabname,i.idxtype,i.clustered as idxcluster,i.indexkeys::lvarchar as indexkeys, t.flags
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
            row.setTableSqlMode(resultSet.getInt("flags"));
            return row;
        });

        if (indexInfo == null || indexInfo.getName() == null){
            return null;
        }
        indexInfo.setIndexCols(getIdxCols(connection,indexInfo.getIndexCols(),colnameList,indexInfo.getTableSqlMode()));
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
    @Override
    public String printIndex(Connection connection, String indexname) throws SQLException {
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
        ddl.append(" ").append(getName(getIndexNameBySqlMode(indexInfo.getName(),indexInfo.getTableSqlMode()),indexInfo.getTableSqlMode())).append(" ON ");
        // 表名(索引字段（函数索引字段）列表)
        ddl.append(getName(indexInfo.getTableName(),indexInfo.getTableSqlMode())).append("(").append(indexInfo.getIndexCols()).append(")");
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
    @Override
    public String printTrigger(Connection connection, String triggername) throws SQLException {
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
            sequenceInfo.setSeqSqlMode(rs.getInt("flags"));
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
    @Override
    public String printSequence(Connection connection, String sequencename) throws SQLException {
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
        ddl.append(getName(sequenceInfo.getName(),sqlmode));
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
            synonymInfo.setSynonymSqlMode(rs.getInt("flags"));
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
    @Override
    public String printSynonym(Connection connection, String synonymname) throws SQLException {
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
        ddl.append("SYNONYM ").append(getName(synonymInfo.getName(),sqlmode)).append(" FOR ");
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
            ddl.append(getName(synonymInfo.getRTabName(),sqlmode));
            // 同义词指向本库
        } else {
            // owner 不再打印
            // ddl.append("\"").append(synonymInfo.getLOwner().trim()).append("\".");
            // table name
            ddl.append(getName(synonymInfo.getLTabName(),sqlmode));
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
        viewInfo.setViewBody(viewBody.toString());
        viewInfo.setFlags(flags);
        viewInfo.setViewSqlMode(flags);
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
    @Override
    public String printView(Connection connection, String viewname) throws SQLException {
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
        ddl.append(viewInfo.getViewBody());
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
                    procedureInfo.setProcBody(procBody.toString().trim());
                    procedureInfo.setProcFlags(procFlags);
                    procedureInfo.setProcId(procId);
                    procedureInfo.setProcSqlMode(procFlags);
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
                procedureInfo.setProcBody(procBody.toString().trim());
                procedureInfo.setProcFlags(procFlags);
                procedureInfo.setProcId(procId);
                procedureInfo.setProcSqlMode(procFlags);
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
                procedureInfo.setProcBody(procBody.toString().trim());
                procedureInfo.setProcFlags(procFlags);
                procedureInfo.setProcId(procId);
                procedureInfo.setProcSqlMode(procFlags);
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
    @Override
    public String printFunction(Connection connection, String objectName) throws SQLException {
        return printProcedure(connection, objectName);
    }

    @Override
    public String printProcedure(Connection connection, String procdefine) throws SQLException {
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
            ddl.append(procedure.getProcBody());
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
    @Override
    public String printDatabase(Connection connection,String databasename) throws SQLException {
        return printDatabase(connection, databasename, null);
    }

    @Override
    public long countDatabaseExportItems(Connection connection, String databasename) throws SQLException {
        String preDatabase = getActiveDbname(connection);
        boolean switchedDatabase = !databasename.equals(preDatabase);
        Throwable pending = null;
        try {
            if (switchedDatabase) {
                setActiveDbname(connection, databasename);
            }
            GbaseMetadataRepository metadataRepository = new GbaseMetadataRepository();
            boolean filterType = metadataRepository.hasSysProcTypeColumn(connection);
            SqlRunner runner = new SqlRunner(connection, DEFAULT_QUERY_TIMEOUT_SECONDS);
            long functionCount = metadataRepository.getFunctionCount(connection, filterType);
            long procedureCount = metadataRepository.getProcedureCount(connection, filterType);
            long tableCount = metadataRepository.getUserTablesCount(connection);
            long synonymCount = metadataRepository.getSynonymCount(connection);
            long sequenceCount = metadataRepository.getSequenceCount(connection);
            long viewCount = metadataRepository.getViewCount(connection);
            long indexCount = metadataRepository.getIndexCount(connection);
            long foreignKeyCount = countForeignKeyExportItems(runner);
            long triggerCount = metadataRepository.getTriggerCount(connection);
            long total = functionCount
                    + procedureCount
                    + tableCount
                    + synonymCount
                    + sequenceCount
                    + viewCount
                    + indexCount
                    + foreignKeyCount
                    + triggerCount;
            log.info(
                    "Database export object counts for {}: function={}, procedure={}, table={}, synonym={}, sequence={}, view={}, index={}, foreignKey={}, trigger={}, total={}",
                    databasename,
                    functionCount,
                    procedureCount,
                    tableCount,
                    synonymCount,
                    sequenceCount,
                    viewCount,
                    indexCount,
                    foreignKeyCount,
                    triggerCount,
                    total
            );
            return total;
        } catch (SQLException e) {
            pending = e;
            throw e;
        } catch (RuntimeException e) {
            pending = e;
            throw e;
        } catch (Error e) {
            pending = e;
            throw e;
        } finally {
            if (switchedDatabase) {
                try {
                    setActiveDbname(connection, preDatabase);
                } catch (SQLException restoreException) {
                    if (pending != null) {
                        pending.addSuppressed(restoreException);
                    } else {
                        throw restoreException;
                    }
                }
            }
        }
    }

    /**
     * 输出printDatabase的头部信息
     * @param databasename
     * @param productversion
     * @return
     */
    private String printDBAll_00_Header(String databasename,String productversion){
        StringBuilder ddl = new StringBuilder();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String datestr = formatter.format(new Date(System.currentTimeMillis()));
        ddl.append("-- ### product version : ").append(productversion).append("\n");
        ddl.append("-- ### export database : ").append(databasename).append("\n");
        ddl.append("-- ### export datetime : ").append(datestr).append("\n\n");
        return ddl.toString();
    }

    /**
     * 输出printDatabase的函数和存储过程
     * @param connection
     * @param displaysqlmode
     * @return
     */
    private String printDBAll_10_Procedure(Connection connection, boolean displaysqlmode,long[] completed,LongConsumer progressCallback) throws SQLException {
        StringBuilder ddl = new StringBuilder();
        String sqlstr = """
                select procname,procid,procflags,seqno,procbody from ( 
                    select p.procname,p.procid,p.procflags,b.seqno,b.data as procbody 
                    from sysprocedures p, sysprocbody b  
                    where p.procid = b.procid 
                    and p.mode in ('O') 
                    and b.datakey = 'T' 
                    and p.isproc = 'f' 
                    order by p.procid asc,b.seqno asc
                ) 
                union all 
                select procname,procid,procflags,seqno,procbody from ( 
                    select p.procname,p.procid,p.procflags,b.seqno,b.data as procbody 
                    from sysprocedures p, sysprocbody b  
                    where p.procid = b.procid 
                    and p.mode in ('O') 
                    and b.datakey = 'T' 
                    and p.isproc = 't' 
                    order by p.procid asc,b.seqno asc
                );
            """;
        ArrayList<Procedure> procedureInfoArrayList = new ArrayList<>();
        String procname = null;
        int procflags = 0;
        String procbody = null;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
                ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()){
                procname = resultSet.getString("procname");
                procflags = resultSet.getInt("procflags");
                procbody = rtrimascii0(resultSet.getString("procbody"));
            }
            while (resultSet.next()){
                if (resultSet.getInt("seqno") == 1){
                    Procedure procedureInfo = new Procedure(procname);
                    procedureInfo.setProcFlags(procflags);
                    procedureInfo.setProcBody(procbody);
                    procedureInfo.setProcSqlMode(procflags);
                    procedureInfoArrayList.add(procedureInfo);
                    procname = resultSet.getString("procname");
                    procflags = resultSet.getInt("procflags");
                    procbody = rtrimascii0(resultSet.getString("procbody"));
                } else {
                    procbody = procbody + rtrimascii0(resultSet.getString("procbody"));
                }
            }
        }
        if (procname != null){
            Procedure procedureInfo = new Procedure(procname);
            procedureInfo.setProcFlags(procflags);
            procedureInfo.setProcBody(procbody);
            procedureInfo.setProcSqlMode(procflags);
            procedureInfoArrayList.add(procedureInfo);
        }
        // 打印 函数和存储过程
        ddl = ddl.append("-- ### START: output function and procedure.\n");
        for(int i=0;i<procedureInfoArrayList.size();i++){
            ddl.append("-- function or procedure : ").append(procedureInfoArrayList.get(i).getName()).append("\n");
            if (displaysqlmode){
                ddl.append("SET ENVIRONMENT SQLMODE '").append(procedureInfoArrayList.get(i).getProcSqlMode()).append("';\n");
            }
            ddl.append(procedureInfoArrayList.get(i).getProcBody()) ;
            
            if ("Oracle".equals(procedureInfoArrayList.get(i).getProcSqlMode())){
                ddl.append("\n/");
            }
            ddl.append("\n\n");
            completed[0] = advanceDatabaseExportProgress(progressCallback, completed);
        }
        ddl.append("-- ### FINISH: output function and procedure.\n\n");
        return ddl.toString();
    }

    /**
     * 获取所有表注释信息，含segno和不含segno两种情况；comment原样，后续需要处理单引号问题。
     * @param connection
     * @return
     * @throws SQLException
     */
    private String[] getAllTableComment(Connection connection, int maxtabid, int commflags) throws SQLException {
        String[] tableCommentArray = new String[maxtabid + 1];
        ArrayList<Table> tableCommentList = new ArrayList<>();
        String sqlstr = "";
        if (commflags == 11){           // 含segno
            sqlstr = """
                SELECT t.tabname, t.tabid, c.segno, c.comments 
                FROM syscomms c, systables t 
                WHERE c.tabid = t.tabid 
                ORDER BY t.tabid ASC, c.segno ASC;  
                """;
        } else if (commflags == 1){     // 不含segno
            sqlstr = """
                SELECT t.tabname, t.tabid, c.comments 
                FROM syscomms c, systables t 
                WHERE c.tabid = t.tabid 
                ORDER BY t.tabid ASC;     
                """;
        }
        // 获取所有数据库表的注释信息
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
                ResultSet resultSet = preparedStatement.executeQuery()) {
            while(resultSet.next()){
                Table tableComment = new Table(resultSet.getString("tabname"));
                tableComment.setTableId(resultSet.getInt("tabid"));
                tableComment.setTableComm(trim(resultSet.getString("comments")));
                tableCommentList.add(tableComment);
            }
        }
        // 将注释信息放入数组，数组下标为tabid，合并多行注释
        int tabid = 0;
        for (int i=0;i<tableCommentList.size();i++){
            Table tableComment = tableCommentList.get(i);            
            if (tableComment.getTableId() != tabid){
                // 新表，直接放入结果数组            
                tabid = tableComment.getTableId();
                tableCommentArray[tabid] = tableComment.getTableComm();
            } else {
                // 同一表，进行注释合并
                String oldComm = tableCommentArray[tabid];
                tableCommentArray[tabid] = oldComm + tableComment.getTableComm();
            }
        }        
        return tableCommentArray;
    }

    /**
     * 获取所有表的列信息，去除物化视图 "mtab$_"。每行唯一。
     * @param connection
     * @param maxtabid
     * @return
     * @throws SQLException
     */
    private ArrayList<ColumnsInfo> getAllTableColumnsInfos(Connection connection, int dbversion) throws SQLException {
        ArrayList<ColumnsInfo> columnsInfoUniqueList = new ArrayList<>();
        ArrayList<ColumnsInfo> columnsInfoList = new ArrayList<>();
        // 对于虚拟表，sysdefaultsexpr可能多行定义
        String sqlstr = """
                SELECT
                   t.tabid tabid
                  ,t.flags flags
                  ,sc.colno colno
                  ,sc.colname colname
                  ,sc.coltype,sc.collength
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
                LEFT JOIN syscolumns sc ON t.tabid = sc.tabid and bitand(sc.colattr,1) = 0
                LEFT JOIN sysdefaults df ON (t.tabid = df.tabid AND sc.colno = df.colno)
                LEFT JOIN sysdefaultsexpr de ON (t.tabid = de.tabid AND sc.colno = de.colno and de.type='T')
                LEFT JOIN sysxtdtypes sx ON (sx.type in (sc.coltype,mod(sc.coltype,256)) AND sx.extended_id = sc.extended_id)
                where t.tabid > (select tabid from systables where tabname = ' VERSION') 
                and t.tabtype = 'T' 
                and t.tabname not like 'mtab$\\_%' 
                ORDER BY t.tabid ASC, sc.colno ASC;
                """;
        // 获取数据库查询结果集arraylist，需进行可能的多行合并
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while(resultSet.next()){
                ColumnsInfo columnsInfo = new ColumnsInfo();
                String sqlmode = getTableSqlModeByFlags(resultSet.getInt("flags"));
                String coltypename = getColTypeName(resultSet.getInt("coltype"), 
                                                        resultSet.getInt("collength"), 0, 0, 
                                                        resultSet.getString("sxname"),
                                                        sqlmode
                                                    ); 
                columnsInfo.setTabId(resultSet.getInt("tabid"));
                columnsInfo.setColNo(resultSet.getInt("colno"));
                columnsInfo.setColName(resultSet.getString("colname"));
                columnsInfo.setColType(coltypename);
                columnsInfo.setColLength(resultSet.getInt("collength"));  
                columnsInfo.setColTypePS(resultSet.getInt("collength"), coltypename, dbversion); 
                columnsInfo.setIsNullable((resultSet.getInt("isnullable") == 1));
                columnsInfo.setIsPK((resultSet.getInt("ispk") == 1));
                columnsInfo.setColDefType(resultSet.getString("coldeftype"));
                // 可能存在多行默认值定义的情况，后续进行合并
                columnsInfo.setColDef(trim(resultSet.getString("coldef")));
                columnsInfo.setIsAutoincrement((resultSet.getInt("isautoincrement") == 1));
                columnsInfoList.add(columnsInfo);
            }
        }
        // 进行多行字段默认值的合并
        for (ColumnsInfo columnsInfo : columnsInfoList){
            int size = columnsInfoUniqueList.size();
            if (size > 0 && (columnsInfoUniqueList.get(size - 1).getTabId() == columnsInfo.getTabId()) 
                    && (columnsInfoUniqueList.get(size - 1).getColNo() == columnsInfo.getColNo())){
                // 多行默认值定义，进行合并
                ColumnsInfo lastColumnsInfo = columnsInfoUniqueList.get(size - 1);
                lastColumnsInfo.setColDef(lastColumnsInfo.getColDef() + columnsInfo.getColDef());
                columnsInfoUniqueList.set(size - 1, lastColumnsInfo);
            } else {
                columnsInfoUniqueList.add(columnsInfo);
            }    
        }
        return columnsInfoUniqueList;
    }

    /**
     * 根据flags获取表的sqlmode
     * @param flags
     * @return
     */
    private static String getTableSqlModeByFlags(int flags){
        String sqlmode = "GBase";
        if ((flags & 16384) == 16384){
            sqlmode = "Oracle";
        } else if ((flags & 65536) == 65536){
            sqlmode = "MySQL";
        }
        return sqlmode;
    }

    /**
     * 获取所有表的列注释信息。每行唯一。comment原样，后续需要处理单引号问题。
     * @param connection
     * @param maxtabid
     * @param commflags
     * @return
     * @throws SQLException
     */
    private ArrayList<ColumnsCommInfo> getAllTableColumnsComments(Connection connection,int commflags) throws SQLException {
        ArrayList<ColumnsCommInfo> columnsCommInfoUniqueList = new ArrayList<>(); 
        ArrayList<ColumnsCommInfo> columnsCommInfoList = new ArrayList<>();         // 所有的字段注释信息。
        String sqlstr = "";
        if (commflags == 11){   // 含segno
            sqlstr = """
                select cc.tabid, c.colname,cc.colno,cc.comments,cc.segno 
                from syscolcomms cc, systables t, syscolumns c 
                where cc.tabid = t.tabid  
                and t.tabid = c.tabid 
                and cc.colno = c.colno  
                order by cc.tabid asc, cc.colno asc, cc.segno asc;
                """;
        } else if (commflags == 1){ // 不含segno
            sqlstr = """
                select cc.tabid,c.colname,cc.colno,cc.comments 
                from syscolcomms cc, systables t, syscolumns c 
                where cc.tabid = t.tabid  
                and t.tabid = c.tabid 
                and cc.colno = c.colno  
                order by cc.tabid asc, cc.colno asc;   
                """;
        }
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while(resultSet.next()){
                ColumnsCommInfo columnsCommInfo = new ColumnsCommInfo();
                columnsCommInfo.setTabId(resultSet.getInt("tabid"));
                columnsCommInfo.setColName(resultSet.getString("colname"));
                columnsCommInfo.setColNo(resultSet.getInt("colno"));
                columnsCommInfo.setColComm(resultSet.getString("comments"));
                columnsCommInfoList.add(columnsCommInfo);
            }
        }
        // 合并到数组中，同时合并多行注释
        for (ColumnsCommInfo columnsCommInfo : columnsCommInfoList){
            int size = columnsCommInfoUniqueList.size();
            if (size > 0 && (columnsCommInfoUniqueList.get(size - 1).getTabId() == columnsCommInfo.getTabId()) 
                    && (columnsCommInfoUniqueList.get(size - 1).getColNo() == columnsCommInfo.getColNo())){
                // 多行注释定义，进行合并
                ColumnsCommInfo lastColumnsCommInfo = columnsCommInfoUniqueList.get(size - 1);
                lastColumnsCommInfo.setColComm(lastColumnsCommInfo.getColComm() + columnsCommInfo.getColComm());
                columnsCommInfoUniqueList.set(size - 1, lastColumnsCommInfo);
            } else {
                columnsCommInfoUniqueList.add(columnsCommInfo);
            } 
        }
        return columnsCommInfoUniqueList;
    }

    /**
     * 获取所有表的check约束信息。每行唯一。checktext原样。
     * @param connection
     * @return
     * @throws SQLException
     */
    private ArrayList<CheckInfo> getAllTableCheckInfos(Connection connection) throws SQLException {
        ArrayList<CheckInfo> checkInfoUniqueList = new ArrayList<>();
        ArrayList<CheckInfo> checkInfoList = new ArrayList<>();
        String sqlstr = """
                SELECT t.tabid, con.constrname, chk.seqno as checkseqno, chk.checktext as checktext
                FROM sysconstraints con, syschecks chk, systables t
                WHERE con.constrid = chk.constrid
                AND con.tabid = t.tabid
                AND con.constrtype = 'C'
                AND chk.TYPE = 'T'
                ORDER BY t.tabid asc,con.constrname asc, chk.seqno asc;
                """;
        // 有checkseqno的情况，需要进行合并
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while(resultSet.next()){
                CheckInfo checkInfo = new CheckInfo();
                checkInfo.setTableId(resultSet.getInt("tabid"));
                checkInfo.setConstrName(resultSet.getString("constrname"));
                checkInfo.setCheckText(resultSet.getString("checktext"));
                checkInfoList.add(checkInfo);
            }
        }
        // 进行多行check的合并
        for (CheckInfo checkInfo : checkInfoList){
            int size = checkInfoUniqueList.size();
            if (size > 0 && (checkInfoUniqueList.get(size - 1).getTableId() == checkInfo.getTableId()) 
                    && (checkInfoUniqueList.get(size - 1).getConstrName().equals(checkInfo.getConstrName()))){
                // 多行check定义，进行合并
                CheckInfo lastCheckInfo = checkInfoUniqueList.get(size - 1);
                lastCheckInfo.setCheckText(lastCheckInfo.getCheckText() + checkInfo.getCheckText());
                checkInfoUniqueList.set(size - 1, lastCheckInfo);
            } else {
                checkInfoUniqueList.add(checkInfo);
            } 
        }
        return checkInfoUniqueList;
    }

    private String getIndexCols(Connection connection, String idxColsString, ArrayList<ColumnsInfo> columnsInfoList, String sqlmode) throws SQLException {
        // idxColsString: <-234>(1, '2') [1], -3 [1]
        // 索引字段（函数索引字段）以，为分隔符
        // 示例中：<-234>(1, '2', '4') [1] 表示 函数号-234（内置函数），对应的字段是1，-(负值，如有)表示desc排序，'2','4'用于多值参数的函数，[1] 是读取方式（默认该值）；-3表示第三个字段desc排序。
        StringBuilder idxCols = new StringBuilder();
        String funcname = "";
        String funcparam = "";
        String sortby = ",";
        String currcolname = "";
        int currcolno = 0;
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
                idxCols.append(getName(funcname,sqlmode)).append("(");
                // 获取字段 及 排序方式 及多参函数参数
                String tmpstr_func = tmpstr.substring(tmpstr.indexOf("(")+1,tmpstr.indexOf(")"));
                if (tmpstr_func.indexOf(",") > 0){  // 多值参数，有其它参数
                    currcolno = Integer.valueOf(tmpstr_func.substring(0,tmpstr_func.indexOf(",")));
                    funcparam = tmpstr_func.substring(tmpstr_func.indexOf(",")+1).trim();
                } else {                            // 单值
                    currcolno = Integer.valueOf(tmpstr_func);
                }
                sortby = (currcolno<0)?" DESC,":",";
                int colno = Math.abs(currcolno);
                currcolname = columnsInfoList.stream()
                        .filter(c -> c.getColNo() == colno)
                        .map(ColumnsInfo::getColName)
                        .findFirst()
                        .orElse("");
                idxCols.append(getName(currcolname,sqlmode));
                if ("".equals(funcparam)){
                    idxCols.append(")");
                } else {
                    idxCols.append(",").append(funcparam).append(")");
                }
                idxCols.append(sortby);
            } else {                                // 普通字段 -3 [1]
                currcolno = Integer.valueOf(tmpstr.substring(0,tmpstr.indexOf(" ")));
                sortby = (currcolno<0)?" DESC,":",";
                int colno = Math.abs(currcolno);
                currcolname = columnsInfoList.stream()
                        .filter(c -> c.getColNo() == colno)
                        .map(ColumnsInfo::getColName)
                        .findFirst()
                        .orElse("");
                idxCols.append(getName(currcolname,sqlmode)).append(sortby);
            }
        }
        if (idxCols.length() > 0){
           idxCols.deleteCharAt(idxCols.length() - 1);
        } 
        return idxCols.toString();
    }

    /**
     * 获取所有表的主键信息。每行唯一。
     * @param connection
     * @return
     * @throws SQLException
     */
    private ArrayList<PrimaryKeyInfo> getAllTablePrimaryKeyInfos(Connection connection, ArrayList<ColumnsInfo> columnsInfoList) throws SQLException {
        ArrayList<PrimaryKeyInfo> primaryKeyInfoList = new ArrayList<>();
        String sqlstr = """
                SELECT t.tabid, t.flags, con.constrname,con.constrtype,idx.indexkeys::lvarchar as idxcols 
                FROM sysconstraints con, sysindices idx, systables t 
                WHERE con.idxname = idx.idxname 
                AND con.tabid = t.tabid 
                AND con.constrtype in ('P','U')
                AND LEFT(con.constrname,1) != ' '
                ORDER BY t.tabid ASC;
                """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while(resultSet.next()){
                PrimaryKeyInfo primaryKeyInfo = new PrimaryKeyInfo();
                primaryKeyInfo.setTableId(resultSet.getInt("tabid"));
                primaryKeyInfo.setConstrName(resultSet.getString("constrname"));
                primaryKeyInfo.setConstrType(resultSet.getString("constrtype"));
                // 这里需要将序号转换为列名。
                String sqlmode = getTableSqlModeByFlags(resultSet.getInt("flags"));
                primaryKeyInfo.setIdxCols(getIndexCols(connection, resultSet.getString("idxcols"), columnsInfoList, sqlmode));
                primaryKeyInfoList.add(primaryKeyInfo);
            }
        }
        return primaryKeyInfoList;
    }

    /**
     * 获取所有表的分区信息。
     * @param connection
     * @return
     * @throws SQLException
     */
    private ArrayList<FragmentInfo> getAllTableFragmentInfos(Connection connection) throws SQLException {
        ArrayList<FragmentInfo> fragmentInfoList = new ArrayList<>();
        String sqlstr = """
                SELECT tab.tabid, frag.colno, frag.strategy, frag.evalpos, frag.exprtext, frag.flags, frag.dbspace, frag.partition
                FROM sysfragments frag, systables tab
                WHERE frag.tabid = tab.tabid
                AND frag.fragtype = 'T'
                ORDER BY tab.tabid ASC,frag.evalpos ASC;
                """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while(resultSet.next()){
                FragmentInfo fragmentInfo = new FragmentInfo();
                fragmentInfo.setTableId(resultSet.getInt("tabid"));
                fragmentInfo.setColNo(resultSet.getInt("colno"));
                fragmentInfo.setStrategy(resultSet.getString("strategy"));
                fragmentInfo.setEvalpos(resultSet.getInt("evalpos"));
                fragmentInfo.setExprtext(resultSet.getString("exprtext"));
                fragmentInfo.setFlags(resultSet.getInt("flags"));
                fragmentInfo.setDbspace(resultSet.getString("dbspace"));
                fragmentInfo.setPartition(resultSet.getString("partition"));
                fragmentInfoList.add(fragmentInfo);
            }
        }
        return fragmentInfoList;
    }

    /**
     * 获取所有表的外部表定义信息。
     * @param connection
     * @return
     * @throws SQLException
     */
    private ArrayList<ExtTableInfo> getAllExtTableInfos(Connection connection) throws SQLException {
        ArrayList<ExtTableInfo> extTableInfoList = new ArrayList<>();
        String sqlstr = """
                SELECT t.tabid AS tabid, t.tabname as tablename, e.fmttype as formattype, e.codeset as codeset, e.recdelim as recorddelimiter,
                e.flddelim as fielddelimiter, e.datefmt as dateformat, e.moneyfmt as moneyformat, e.maxerrors as maxerrors,
                e.rejectfile as rejectfile, e.flags as flags, e.ndfiles as numdfiles
                FROM systables t, sysexternal e
                WHERE t.tabid = e.tabid
                ORDER BY t.tabid ASC;
                """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while(resultSet.next()){
                ExtTableInfo extTableInfo = new ExtTableInfo();
                extTableInfo.setTableId(resultSet.getInt("tabid"));
                extTableInfo.setTableName(resultSet.getString("tablename"));
                extTableInfo.setFormatType(resultSet.getString("formattype"));
                extTableInfo.setCodeSet(resultSet.getString("codeset"));
                extTableInfo.setRecordDelimiter(resultSet.getString("recorddelimiter"));
                extTableInfo.setFieldDelimiter(resultSet.getString("fielddelimiter"));
                extTableInfo.setDateFormat(resultSet.getString("dateformat"));
                extTableInfo.setMoneyFormat(resultSet.getString("moneyformat"));
                extTableInfo.setMaxErrors(resultSet.getInt("maxerrors"));
                extTableInfo.setRejectFile(resultSet.getString("rejectfile"));
                extTableInfo.setFlags(resultSet.getInt("flags"));
                extTableInfo.setNumDfiles(resultSet.getInt("numdfiles"));
                extTableInfoList.add(extTableInfo);
            }
        }
        return extTableInfoList;
    }

    /**
     * 获取所有表的外部表文件定义信息。
     * @param connection
     * @return
     * @throws SQLException
     */
    private ArrayList<ExtTableDfiles> getAllExtTableDfiles(Connection connection) throws SQLException {
        ArrayList<ExtTableDfiles> extTableDfilesList = new ArrayList<>();
        String sqlstr = """
                SELECT t.tabid AS tabid,t.tabname as tablename,e.dfentry as datafile, e.blobdir as blobdir, e.clobdir as clobdir
                FROM systables t, sysextdfiles e
                WHERE t.tabid = e.tabid
                ORDER BY t.tabid ASC;
                """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while(resultSet.next()){
                ExtTableDfiles extTableDfiles = new ExtTableDfiles();
                extTableDfiles.setTableId(resultSet.getInt("tabid"));
                extTableDfiles.setTableName(resultSet.getString("tablename"));
                extTableDfiles.setDataFile(resultSet.getString("datafile"));
                extTableDfiles.setBlobDir(resultSet.getString("blobdir"));
                extTableDfiles.setClobDir(resultSet.getString("clobdir"));
                extTableDfilesList.add(extTableDfiles);
            }
        }
        return extTableDfilesList;
    }

    /**
     * 构建外部表定义字符串
     * @param extTableInfoList
     * @param extTableDfilesList
     * @return
     */
    private String buildExtTableDefineString(ArrayList<ExtTableInfo> extTableInfoList, ArrayList<ExtTableDfiles> extTableDfilesList) {
        StringBuilder ddl = new StringBuilder();
        if (extTableInfoList.size() > 0){
            ddl.append(";");
            return ddl.toString();
        }
        ExtTableInfo extTableInfo = extTableInfoList.get(0);
        ddl.append("\nUSING ( \n  DATAFILES(\n");
        for (int i = 0; i < extTableInfo.getNumDfiles(); i++) {
            ddl.append("    '").append(trim(extTableDfilesList.get(i).getDataFile()));
            if (extTableDfilesList.get(i).getBlobDir() != null && !"".equals(trim(extTableDfilesList.get(i).getBlobDir()))) {
                ddl.append(";BLOBDIR:").append(trim(extTableDfilesList.get(i).getBlobDir()));
            }
            if (extTableDfilesList.get(i).getClobDir() != null && !"".equals(trim(extTableDfilesList.get(i).getClobDir()))) {
                ddl.append(";CLOBDIR:").append(trim(extTableDfilesList.get(i).getClobDir()));
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
        return ddl.toString();
    }
    
    /**
     * 输出printDatabase的表定义（含主键及约束，不含索引） 
     * @param connection
     * @param displaysqlmode
     * @param completed
     * @param progressCallback
     * @return
     * @throws SQLException
     */
    private String printDBAll_20_Table(Connection connection, boolean displaysqlmode,long[] completed,LongConsumer progressCallback) throws SQLException {
        String patternConstraint = "^[cur]\\d+_\\d+";              // u=unique,r=reference,c=check
        StringBuilder ddl = new StringBuilder();
        ddl.append("-- ### START: output tables.\n");
        int dbversion = getDataBaseProductVersionNumber(connection);
        int commflags = getCommentFlags(connection);
        ArrayList<Table> tableInfoArrayList = new ArrayList<>();
        int maxtabid = 0;
        String sqlstr = """
                select max(tabid) as maxtabid from systables;
                """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while(resultSet.next()){
                maxtabid = resultSet.getInt("maxtabid");
            }
        }

        // 所有表信息，去除物化视图 "mtab$_"，每行唯一。
        sqlstr = """
                select t.tabname,dbinfo('dbname') as tablecatalog, t.owner as tableowner,t.locklevel as locktype,
                t.fextsize as firstextsize, t.nextsize as nextextsize, t.tabtype as tabletype,t.flags as tableflags, t.tabid 
                from systables t 
                where t.tabid > (select tabid from systables where tabname = ' VERSION') 
                and t.tabtype = 'T' 
                and t.tabname not like 'mtab$\\_%' 
                order by t.tabid asc;
            """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while(resultSet.next()){
                Table tableInfo = new Table(resultSet.getString("tabname"));
                tableInfo.setTableCatalog(resultSet.getString("tablecatalog"));
                tableInfo.setTableOwner(trim(resultSet.getString("tableowner")));
                tableInfo.setLockType(resultSet.getString("locktype"));
                tableInfo.setFirstExtSize(resultSet.getInt("firstextsize"));
                tableInfo.setNextExtSize(resultSet.getInt("nextextsize"));
                tableInfo.setTableTypeCode(resultSet.getString("tabletype"));
                tableInfo.setFlags(resultSet.getInt("tableflags"));
                tableInfo.setTableSqlMode(resultSet.getInt("tableflags"));
                tableInfo.setDbVersion(dbversion);
                tableInfo.setTableId(resultSet.getInt("tabid"));
                tableInfoArrayList.add(tableInfo);
            }
        }

        // 合并表注释到表信息中
        String[] tableCommentArray = getAllTableComment(connection,maxtabid,commflags);
        for (Table tableInfo : tableInfoArrayList){
            int tabid = tableInfo.getTableId();
            if (tableCommentArray[tabid] != null){
                tableInfo.setTableComm(tableCommentArray[tabid]);
            }
        }

        // 获取每个表的列信息。
        ArrayList<ColumnsInfo> columnsInfoList = getAllTableColumnsInfos(connection, dbversion);
        ArrayList<ColumnsCommInfo> columnsCommInfoList = getAllTableColumnsComments(connection, commflags);
        // 合并列注释到列信息中
        for (ColumnsInfo columnsInfo : columnsInfoList){
            for (ColumnsCommInfo columnsCommInfo : columnsCommInfoList){
                if (columnsInfo.getTabId() == columnsCommInfo.getTabId() 
                        && columnsInfo.getColNo() == columnsCommInfo.getColNo()){
                    columnsInfo.setColComm(columnsCommInfo.getColComm());
                    break;
                }
            }
        }

        // 表内check
        ArrayList<CheckInfo> checkInfoList = getAllTableCheckInfos(connection);

        // 表内主键
        ArrayList<PrimaryKeyInfo> primaryKeyInfoList = getAllTablePrimaryKeyInfos(connection, columnsInfoList);

        // 表分区信息
        ArrayList<FragmentInfo> fragmentInfoList = getAllTableFragmentInfos(connection);

        // 外部表定义信息
        ArrayList<ExtTableInfo> extTableInfoList = getAllExtTableInfos(connection);
        ArrayList<ExtTableDfiles> extTableDfilesList = getAllExtTableDfiles(connection);


        // 开始生成表的DDL信息，表主体（含约束、主键），外部表定义信息（如有），区段信息。
        StringBuilder commBuilder = new StringBuilder();
        for (int i=0; i<tableInfoArrayList.size();i++){
            Table tableInfo = tableInfoArrayList.get(i);
            ddl.append("-- table : ").append(tableInfo.getTableOwner()).append(".").append(tableInfo.getName()).append("\n");
            // 单独的表注释语句（for oracle and gbase mode）
            if (tableInfo.getTableComm() != null){
                commBuilder.append("COMMENT ON TABLE ").append(getName(tableInfo.getName(),tableInfo.getTableSqlMode()))
                .append(" IS '")
                .append(tableInfo.getTableComm().replace("'", "''")).append("';\n");
            }
            if (displaysqlmode){
                ddl.append("SET ENVIRONMENT SQLMODE '").append(tableInfo.getTableSqlMode()).append("';\n");
            }
            ddl.append("CREATE ");
            // global temporary
            if (! "".equals(tableInfo.getTableGlobalTemporary())){
                ddl.append(tableInfo.getTableGlobalTemporary()).append(" ");
            }
            // external & raw
            ddl.append(tableInfo.getTableFlag()).append("TABLE ");
            ddl.append(getName(tableInfo.getName(),tableInfo.getTableSqlMode())).append(" (\n");

            // 输出列定义。需从columnsInfoList中筛选出当前表的列信息，进行输出。
            ArrayList<ColumnsInfo> currentTableColumnsInfoList = columnsInfoList.stream()
                .filter(columnsInfo -> columnsInfo.getTabId() == tableInfo.getTableId()).collect(Collectors.toCollection(ArrayList::new));

            for (int j=0; j<currentTableColumnsInfoList.size();j++){
                ColumnsInfo columnsInfo = currentTableColumnsInfoList.get(j);
                // 字段名
                ddl.append("  ").append(getName(columnsInfo.getColName(),tableInfo.getTableSqlMode()));
                // 字段类型
                ddl.append(" ").append(getColTypeName(columnsInfo.getColType(),columnsInfo.getColLength()));
                // 默认值 
                if (columnsInfo.getColDefType() != null){
                    ddl.append(" DEFAULT ").append(getDefaults(columnsInfo.getColType(),
                        columnsInfo.getColDefType(),columnsInfo.getColDef()));
                }
                // 非空
                if (! columnsInfo.isIsNullable()){
                    ddl.append(" NOT NULL");
                }
                // 注释处理（MySQL表定义内注释，Oracle和GBase表定义外注释）
                if (columnsInfo.getColComm() != null){
                    if ("MySQL".equals(tableInfo.getTableSqlMode())){
                        ddl.append(" COMMENT '").append(columnsInfo.getColComm().replace("'", "''")).append("'");
                    } else {
                        commBuilder.append("COMMENT ON COLUMN ").append(getName(tableInfo.getName(),tableInfo.getTableSqlMode()))
                        .append(".").append(getName(columnsInfo.getColName(),tableInfo.getTableSqlMode()))
                        .append(" IS '")
                        .append(columnsInfo.getColComm().replace("'", "''")).append("';\n");
                    }
                }
                if (j < currentTableColumnsInfoList.size() - 1){
                    ddl.append(",\n");
                }
            }

            // 输出check约束定义。需从checkInfoList中筛选出当前表的check信息，进行输出。
            ArrayList<CheckInfo> currentTableCheckInfoList = checkInfoList.stream()
                .filter(checkInfo -> checkInfo.getTableId() == tableInfo.getTableId()).collect(Collectors.toCollection(ArrayList::new));
            appendCheckConstraints(ddl, currentTableCheckInfoList, tableInfo.getTableSqlMode(),patternConstraint);
            
            // 输出主键约束定义。需从primaryKeyInfoList中筛选出当前表的主键信息，进行输出。
            ArrayList<PrimaryKeyInfo> currentTablePrimaryKeyInfoList = primaryKeyInfoList.stream()
                .filter(primaryKeyInfo -> primaryKeyInfo.getTableId() == tableInfo.getTableId()).collect(Collectors.toCollection(ArrayList::new));
            appendPrimaryConstraints(ddl, currentTablePrimaryKeyInfoList, tableInfo.getTableSqlMode(),patternConstraint);

            ddl.append("\n) ");
            
            // E 外部表处理，不考虑maxrows，TODO：没处理外部数据类型对应
            if ("E".equals(tableInfo.getTableTypeCode())){
                ArrayList<ExtTableInfo> extTableInfoByTabId = extTableInfoList.stream()
                    .filter(extTable -> extTable.getTableId() == tableInfo.getTableId()).collect(Collectors.toCollection(ArrayList::new));
                ArrayList<ExtTableDfiles> extTableDfilesByTabId = extTableDfilesList.stream()
                    .filter(extTableDfiles -> extTableDfiles.getTableId() == tableInfo.getTableId()).collect(Collectors.toCollection(ArrayList::new));
                buildExtTableDefineString(extTableInfoByTabId, extTableDfilesByTabId);
            } else {        // T 普通表
                ArrayList<FragmentInfo> fragmentInfo = fragmentInfoList.stream()
                    .filter(fragment -> fragment.getTableId() == tableInfo.getTableId()).collect(Collectors.toCollection(ArrayList::new));
                if (fragmentInfo.size() > 0){
                    ddl.append(buildFragmentString(fragmentInfo));
                }
                // 全局临时表级别
                if ("".equals(tableInfo.getTableGlobalTemporary())){
                    ddl.append("\n");
                } else {
                    ddl.append("\n").append(tableInfo.getTableGlobalTemporaryLevel()).append(" ");
                }

                // mysql 模式下表定义后追加表注释
                if ("MySQL".equals(tableInfo.getTableSqlMode()) && tableInfo.getTableComm() != null){
                    ddl.append("COMMENT '").append(tableInfo.getTableComm().replace("'", "''")).append("' ");
                }
                // 区段大小及锁模式
                ddl.append("EXTENT SIZE ").append(tableInfo.getFirstExtSize()).append(" NEXT SIZE ").append(tableInfo.getNextExtSize());
                ddl.append(" LOCK MODE ").append(tableInfo.getLockTypeFunc()).append(";\n");
            }
            
            // 追加输出单独的注释语句，oracle和gbase模式
            ddl.append("\n");
            if ("Oracle".equals(tableInfo.getTableSqlMode()) || "GBase".equals(tableInfo.getTableSqlMode())){
                ddl.append(commBuilder.toString()).append("\n");
             }
            commBuilder.setLength(0);
            completed[0] = advanceDatabaseExportProgress(progressCallback, completed);
        }

        ddl.append("-- ### FINISH: output tables.\n\n");
        return ddl.toString();
    }

    /**
     * 输出printDatabase的同义词
     * @param connection
     * @param displaysqlmode
     * @param completed
     * @param progressCallback
     * @return
     * @throws SQLException
     */
    private String printDBAll_30_Synonym(Connection connection, boolean displaysqlmode,long[] completed,LongConsumer progressCallback) throws SQLException {
        StringBuilder ddl = new StringBuilder();
        ddl.append("-- ### START: output synonym.\n");
        ArrayList<Synonym> synonymInfoArrayList = new ArrayList<>();
        String sqlstr = """
                select t1.tabtype AS syntype,t1.owner AS synowner,t1.tabname AS synname, 
                    s.servername AS rservername,s.dbname AS rdbname,s.owner AS rowner,s.tabname AS rtabname, 
                    syn.owner AS lowner,syn.tabname AS ltabname, t1.flags 
                from syssyntable s 
                LEFT JOIN systables syn ON s.btabid = syn.tabid, systables t1 
                WHERE s.tabid = t1.tabid;
            """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
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
                synonymInfo.setSynonymSqlMode(resultSet.getInt("flags"));
                synonymInfoArrayList.add(synonymInfo);
            }
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
            ddl.append("SYNONYM ").append(getName(synonymInfoArrayList.get(i).getName(),synonymInfoArrayList.get(i).getSynonymSqlMode())).append(" FOR ");
            // 同义词指向remote
            if (synonymInfoArrayList.get(i).getRTabName() != null){      // 同义远程表
                // remote dbname
                ddl.append(trim(synonymInfoArrayList.get(i).getRDbName()));
                // remote servername, can other db
                if (synonymInfoArrayList.get(i).getRServerName() != null && ! "".equals(synonymInfoArrayList.get(i).getRServerName())){
                    ddl.append("@").append(trim(synonymInfoArrayList.get(i).getRServerName()));
                }
                ddl.append(("Oracle".equals(synonymInfoArrayList.get(i).getSynonymSqlMode()))?".":":");
                ddl.append(getName(synonymInfoArrayList.get(i).getRTabName(),synonymInfoArrayList.get(i).getSynonymSqlMode()));
                // 同义词指向本库
            } else {
                ddl.append(getName(synonymInfoArrayList.get(i).getLTabName(),synonymInfoArrayList.get(i).getSynonymSqlMode()));
            }
            ddl.append(";\n\n");
            completed[0] = advanceDatabaseExportProgress(progressCallback, completed);
        }
        ddl.append("-- ### FINISH: output synonym.\n\n");
        return ddl.toString();
    }

    /**
     * 输出printDatabase的序列
     * @param connection
     * @param displaysqlmode
     * @param completed
     * @param progressCallback
     * @return
     * @throws SQLException
     */
    private String printDBAll_40_Sequence(Connection connection, boolean displaysqlmode,long[] completed,LongConsumer progressCallback) throws SQLException {
        StringBuilder ddl = new StringBuilder();
        ddl.append("-- ### START: output sequence.\n");
        ArrayList<Sequence> sequenceInfoArrayList = new ArrayList<>();
        String sqlstr = """
                SELECT t.owner AS seqowner,t.tabname AS seqname,seq.start_val AS startval, 
                    seq.inc_val AS incval,seq.max_val AS maxval,seq.min_val AS minval, 
                    seq.cycle AS iscycle, seq.cache AS cache,seq.order AS isorder, t.flags  
                FROM systables t, syssequences seq 
                WHERE t.tabid = seq.tabid;
            """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()){
                Sequence sequenceInfo = new Sequence(resultSet.getString("seqname"));
                sequenceInfo.setSeqOwner(trim(resultSet.getString("seqowner")));
                sequenceInfo.setStartVal(resultSet.getLong("startval"));
                sequenceInfo.setIncVal(resultSet.getLong("incval"));
                sequenceInfo.setMaxVal(resultSet.getLong("maxval"));
                sequenceInfo.setMinVal(resultSet.getLong("minval"));
                sequenceInfo.setIsCycle(resultSet.getString("iscycle"));
                sequenceInfo.setCache(resultSet.getLong("cache"));
                sequenceInfo.setIsOrder(resultSet.getString("isorder"));
                sequenceInfo.setFlags(resultSet.getInt("flags"));
                sequenceInfo.setSeqSqlMode(resultSet.getInt("flags"));
                sequenceInfoArrayList.add(sequenceInfo);
            }
        }
        for (int i=0;i<sequenceInfoArrayList.size();i++){
            ddl.append("-- sequence : " + sequenceInfoArrayList.get(i).getSeqOwner()).append(".").append(sequenceInfoArrayList.get(i).getName()).append("\n");
            if (displaysqlmode){
                ddl.append("SET ENVIRONMENT SQLMODE '").append(sequenceInfoArrayList.get(i).getSequenceSqlMode()).append("';\n");
            }
            ddl.append( "CREATE SEQUENCE ").append(getName(sequenceInfoArrayList.get(i).getName(),sequenceInfoArrayList.get(i).getSequenceSqlMode()));
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
            completed[0] = advanceDatabaseExportProgress(progressCallback, completed);
        }
        ddl.append("-- ### FINISH: output sequence.\n\n");
        return ddl.toString();
    }

    /**
     * 输出printDatabase的视图
     * @param connection
     * @param displaysqlmode
     * @param completed
     * @param progressCallback
     * @return
     * @throws SQLException
     */
    private String printDBAll_50_View(Connection connection, boolean displaysqlmode,long[] completed,LongConsumer progressCallback) throws SQLException {
        StringBuilder ddl = new StringBuilder();
        ddl.append("-- ### START: output view.\n");
        ArrayList<View> viewInfoArrayList = new ArrayList<>();
        String sqlstr = """
            select t.tabname as viewname,v.seqno,v.viewtext as viewtext, t.flags 
            from systables t,sysviews v  
            where t.tabid = v.tabid 
            AND t.tabid > (SELECT tabid FROM systables WHERE tabname = ' VERSION') 
            and t.tabtype = 'V' 
            order by t.tabid ASC, v.seqno ASC;
            """;
        String viewname = null;
        String viewtext = null;
        int viewflags = 0;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()){
                viewname = resultSet.getString("viewname");
                viewtext = resultSet.getString("viewtext");
                viewflags = resultSet.getInt("flags");
            }
            while (resultSet.next()){
                if (resultSet.getInt("seqno") == 0){
                    View viewInfo = new View(viewname);
                    viewInfo.setViewBody(trim(viewtext));
                    viewInfo.setFlags(viewflags);
                    viewInfo.setViewSqlMode(viewflags);
                    viewInfoArrayList.add(viewInfo);
                    viewname = resultSet.getString("viewname");
                    viewtext = resultSet.getString("viewtext");
                    viewflags = resultSet.getInt("flags");
                } else {
                    viewtext = viewtext + resultSet.getString("viewtext");
                }
            }
        }
        if (viewname != null){
            View viewInfo = new View(viewname);
            viewInfo.setViewBody(trim(viewtext));
            viewInfo.setFlags(viewflags);
            viewInfo.setViewSqlMode(viewflags);
            viewInfoArrayList.add(viewInfo);
        }
        for (int i=0;i<viewInfoArrayList.size();i++){
            ddl.append("-- view : ").append(viewInfoArrayList.get(i).getName()).append("\n");
            if (displaysqlmode){
                ddl.append("SET ENVIRONMENT SQLMODE '").append(viewInfoArrayList.get(i).getViewSqlMode()).append("';\n");
            }
            ddl.append(viewInfoArrayList.get(i).getViewBody());
            ddl.append("\n");
            completed[0] = advanceDatabaseExportProgress(progressCallback, completed);
        }
        ddl.append("-- ### FINISH: output view.\n\n");
        return ddl.toString();
    }

    /**
     * 单独生成 tableid, tablename, columnno, columnname 列表。 索引和外键共用
     * @param connection
     * @return
     * @throws SQLException
     */
    private ArrayList<TableWithColumn> getTableWithColumns(Connection connection) throws SQLException{
        // 单独生成 tableid, tablename, columnno, columnname 列表。 索引和外键共用
        ArrayList<TableWithColumn> tableColumnArrayList = new ArrayList<>();
        String sqlstr = """
            SELECT c.tabid,t.tabname,c.colno,c.colname
            FROM syscolumns c, systables t
            WHERE c.tabid = t.tabid 
            AND c.tabid > (SELECT tabid FROM systables WHERE tabname = ' VERSION')
            ORDER BY c.tabid ASC,colno ASC;
            """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            while (resultSet.next()){
                TableWithColumn tableColumn = new TableWithColumn();
                tableColumn.setTableId(resultSet.getInt("tabid"));
                tableColumn.setTableName(resultSet.getString("tabname"));
                tableColumn.setColumnId(resultSet.getInt("colno"));
                tableColumn.setColumnName(resultSet.getString("colname"));
                tableColumnArrayList.add(tableColumn);
            }
        }
        return tableColumnArrayList;
    }

    /**
     * 输出printDatabase的索引
     * TODO：索引分片及存储规则
     * @param connection
     * @param tableColumnArrayList
     * @param displaysqlmode
     * @param completed
     * @param progressCallback
     * @return
     * @throws SQLException
     */
    private String printDBAll_60_Index(Connection connection, ArrayList<TableWithColumn> tableColumnArrayList,
        boolean displaysqlmode,long[] completed,LongConsumer progressCallback) throws SQLException {
        StringBuilder ddl = new StringBuilder();
        // 基于表字段
        ddl.append("-- ### START: output index.\n");
        ArrayList<Index> indexesArrayList = new ArrayList<>();
        String sqlstr = """
            SELECT i.owner idxowner,i.idxname,t.owner as tabowner, t.tabid, t.tabname, t.tabtype,
                i.idxtype,i.clustered as idxcluster,cast(i.indexkeys AS lvarchar) as indexkeys, t.flags
            FROM sysindices i,systables t
            WHERE i.tabid = t.tabid
            AND i.tabid > (SELECT tabid FROM systables WHERE tabname = ' VERSION')
            AND LEFT(i.idxname,1) != ' '
            ORDER BY i.tabid ASC;   
            """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
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
                index.setTableSqlMode(resultSet.getInt("flags"));
                indexesArrayList.add(index);
            }
        }

        for (int i=0; i<indexesArrayList.size();i++){
            if(displaysqlmode){
                ddl.append("SET ENVIRONMENT SQLMODE '").append(indexesArrayList.get(i).getTableSqlMode()).append(("';\n"));
            }
            if ("U".equals(indexesArrayList.get(i).getIdxtype())){
                ddl.append("CREATE UNIQUE INDEX ");
            } else if ("C".equals(indexesArrayList.get(i).getIdxtype())){
                ddl.append("CREATE CLUSTER INDEX ");
            } else {
                ddl.append("CREATE INDEX ");
            }
            ddl.append(getName(getIndexNameBySqlMode(indexesArrayList.get(i).getName(), indexesArrayList.get(i).getTableSqlMode()),
                    indexesArrayList.get(i).getTableSqlMode())).append(" ON ");
            ddl.append(getName(indexesArrayList.get(i).getTableName(),indexesArrayList.get(i).getTableSqlMode())).append("(");
            // 索引字段列表
            ddl.append(getKeyCols(connection, 
                                indexesArrayList.get(i).getTableId(), 
                                indexesArrayList.get(i).getCols(), 
                                tableColumnArrayList,
                                indexesArrayList.get(i).getTableSqlMode()));

            ddl.append(");\n");
            completed[0] = advanceDatabaseExportProgress(progressCallback, completed);
        }
        ddl.append("-- ### FINISH: output index.\n\n");
        return ddl.toString();
    }

    /**
     * 输出printDatabase的外键
     * @param connection
     * @param tableColumnArrayList
     * @param displaysqlmode
     * @param completed
     * @param progressCallback
     * @return
     * @throws SQLException
     */
    private String printDBAll_70_ForeigenKey(Connection connection, ArrayList<TableWithColumn> tableColumnArrayList,
        boolean displaysqlmode,long[] completed,LongConsumer progressCallback) throws SQLException {
        StringBuilder ddl = new StringBuilder();
        ddl.append("-- ### START: output foreigen key.\n");
        String patternConstraint = "^[cur]\\d+_\\d+";
        ArrayList<ForeignKeyInfo> foreignKeyInfoArrayList = new ArrayList<>();
        String sqlstr = """
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
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
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
                foreignKeyInfo.setForeignKeySqlMode(resultSet.getInt("flags"));
                foreignKeyInfoArrayList.add(foreignKeyInfo);
            }
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
                                    tableColumnArrayList,
                                    foreignKeyInfoArrayList.get(i).getforeignKeySqlMode()
                                ));
            
            ddl.append(") REFERENCES ").append(getName(foreignKeyInfoArrayList.get(i).getPkTabname())).append("(");
            // 参考的主键字段列表
            ddl.append(getKeyCols(connection,
                                    foreignKeyInfoArrayList.get(i).getPkTabid(),
                                    foreignKeyInfoArrayList.get(i).getPkCols(),
                                    tableColumnArrayList,
                                    foreignKeyInfoArrayList.get(i).getforeignKeySqlMode()
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
            completed[0] = advanceDatabaseExportProgress(progressCallback, completed);
        }
        ddl.append("-- ### FINISH: output foreigen key.\n\n");
        return ddl.toString();
    }

    /**
     * 输出printDatabase的触发器
     * @param connection
     * @param displaysqlmode
     * @param completed
     * @param progressCallback
     * @return
     * @throws SQLException
     */
    private String printDBAll_80_Trigger(Connection connection,boolean displaysqlmode,long[] completed,LongConsumer progressCallback) throws SQLException {
        StringBuilder ddl = new StringBuilder();
        ddl.append("-- ### START: output trigger.\n");
        ArrayList<Trigger> trigArrayList = new ArrayList<>();
        String sqlstr = """
            SELECT tri.trigid,tri.trigname,tri.mode as trigmode,bdy.seqno as segno,bdy.data as trigbody, obj.state
            FROM systriggers tri, systrigbody bdy, sysobjstate obj
            WHERE tri.trigid = bdy.trigid
            AND tri.trigname = obj.name
            AND obj.objtype = 'T'
            AND (   bdy.datakey = CASE WHEN tri.mode = 'O' THEN 'P' ELSE 'A' END 
                 OR bdy.datakey = CASE WHEN tri.mode = 'O' THEN 'P' ELSE 'D' END
            )
            ORDER BY tri.trigid ASC, bdy.datakey DESC,bdy.seqno ASC;    
            """;
        String trigName = null;
        String trigBoday = null;
        String trigMode = null;
        boolean trigDisabled = false;
        int trigId = 0;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr);
             ResultSet resultSet = preparedStatement.executeQuery()) {
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
            completed[0] = advanceDatabaseExportProgress(progressCallback, completed);
        }
        ddl.append("-- ### FINISH: output trigger.\n\n");
        return ddl.toString();
    }

    @Override
    public DatabaseDdlParts exportDatabaseDdlParts(Connection connection,
                                                   String databasename,
                                                   LongConsumer progressCallback) throws SQLException {
        // 顺序暂定：函数->存储过程->表（含主键、索引、约束）->同义词（等）-> 序列 -> 视图（可能有依赖关系，先后顺序）
        // TODO: 需要增加自定义类型，自定义转换 等
        StringBuilder preDataDdl = new StringBuilder();
        StringBuilder postDataDdl = new StringBuilder();
        String preDatabase = getActiveDbname(connection);
        String productversion = getDataBaseProductVersion(connection);
        int dbVersion = getDataBaseProductVersionNumber(connection);
        boolean displaysqlmode = displaySqlMode(dbVersion);
        boolean switchedDatabase = ! databasename.equals(preDatabase);
        Throwable pending = null;
        long[] completed = new long[1];

        // 00，输出头部信息
        preDataDdl.append(printDBAll_00_Header(databasename,productversion));
        
        try {
            // 变更激活库为当前
            if (switchedDatabase){
                setActiveDbname(connection,databasename);
            }
            // 10, 导出自定义函数和存储过程。 procname, procbody, procflags
            preDataDdl.append(printDBAll_10_Procedure(connection,displaysqlmode,completed,progressCallback));            
            // 20, 导出表结构
            preDataDdl.append(printDBAll_20_Table(connection,displaysqlmode,completed,progressCallback));
            // 30, 同义词
            preDataDdl.append(printDBAll_30_Synonym(connection,displaysqlmode,completed,progressCallback));
            // 40，序列
            preDataDdl.append(printDBAll_40_Sequence(connection,displaysqlmode,completed,progressCallback));
            // 50，视图
            preDataDdl.append(printDBAll_50_View(connection,displaysqlmode,completed,progressCallback));

            // 60（索引）和70（外键）共用，字段列表。数据导入可在此之前。
            ArrayList<TableWithColumn> tableColumnArrayList = getTableWithColumns(connection);
            // 60，索引，需要字段列表
            postDataDdl.append(printDBAll_60_Index(connection,tableColumnArrayList,displaysqlmode,completed,progressCallback));
            // 70，外键，需要字段列表
            postDataDdl.append(printDBAll_70_ForeigenKey(connection,tableColumnArrayList,displaysqlmode,completed,progressCallback));
            // 80，触发器
            postDataDdl.append(printDBAll_80_Trigger(connection,displaysqlmode,completed,progressCallback));     

            return new DatabaseDdlParts(preDataDdl.toString(), postDataDdl.toString());
        } catch (SQLException e) {
            pending = e;
            throw e;
        } catch (RuntimeException e) {
            pending = e;
            throw e;
        } catch (Error e) {
            pending = e;
            throw e;
        } finally {
            if (switchedDatabase){
                try {
                    setActiveDbname(connection,preDatabase);
                } catch (SQLException restoreException) {
                    if (pending != null){
                        pending.addSuppressed(restoreException);
                    } else {
                        throw restoreException;
                    }
                }
            }
        }
    }

    @Override
    public String printDatabase(Connection connection,String databasename, LongConsumer progressCallback) throws SQLException {
        DatabaseDdlParts ddlParts = exportDatabaseDdlParts(connection, databasename, progressCallback);
        return ddlParts.getPreDataSql() + ddlParts.getPostDataSql();
    }

    private static long queryCount(SqlRunner runner, String sql) throws SQLException {
        Long value = runner.queryOne(sql, List.of(), rs -> rs.getLong(1));
        return value == null ? 0 : value;
    }

    private static long countForeignKeyExportItems(SqlRunner runner) throws SQLException {
        return queryCount(runner, """
                select count(*)
                from (
                    SELECT fk_c.constrid
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
                ) t;
                """);
    }

    private static long advanceDatabaseExportProgress(LongConsumer progressCallback, long[] completed) {
        throwIfCancelled();
        long next = completed[0] + 1;
        if (progressCallback != null) {
            progressCallback.accept(next);
        }
        throwIfCancelled();
        return next;
    }

    private static void throwIfCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Database DDL export cancelled");
        }
    }

    /**
     * 生成建表语句的extent块信息
     * @param connection
     * @param tableInfo
     * @return
     * @throws SQLException
     */
    @Deprecated
    private static String buildTableSqlExtent(Connection connection,Table tableInfo) throws SQLException {
        StringBuilder ddl = new StringBuilder();
        ArrayList<FragmentInfo> tableFragmentInfoArrayList = getTableFragmentInfo(connection,tableInfo.getName());
        // 分片规则，Exprtext 的结果可能不对。
        if (tableFragmentInfoArrayList.size() > 0) {
            ddl.append(buildFragmentString(tableFragmentInfoArrayList));
        }

        // 全局临时表级别
        if ("".equals(tableInfo.getTableGlobalTemporary())){
            ddl.append("\n");
        } else {
            ddl.append("\n").append(tableInfo.getTableGlobalTemporaryLevel()).append(" ");
        }
        // 区段大小及锁模式
        ddl.append("EXTENT SIZE ").append(tableInfo.getFirstExtSize()).append(" NEXT SIZE ").append(tableInfo.getNextExtSize());
        ddl.append(" LOCK MODE ").append(tableInfo.getLockTypeFunc()).append(";\n");

        return ddl.toString();
    }

    /**
     * 生成外部表的存储定义部分
     * @param connection
     * @param tableInfo
     * @return
     * @throws SQLException
     */
    @Deprecated
    private static String buildExtTableSql(Connection connection, Table tableInfo) throws SQLException {
        ExtTableInfo extTableInfo = getExtTableInfo(connection,tableInfo.getName());
        ArrayList<ExtTableDfiles> extTableDfilesArrayList = getExtTableDfiles(connection,tableInfo.getName());
        if (extTableInfo==null){
            return ";";
        }
        StringBuilder ddl = new StringBuilder();
        ddl.append("\nUSING ( \n  DATAFILES(\n");
        // DATAFILE
        for(int i=0;i<extTableInfo.getNumDfiles();i++){
            ddl.append("    '").append(trim(extTableDfilesArrayList.get(i).getDataFile()));
            if (extTableDfilesArrayList.get(i).getBlobDir() != null && ! "".equals(trim(extTableDfilesArrayList.get(i).getBlobDir()))){
                ddl.append(";BLOBDIR:").append(trim(extTableDfilesArrayList.get(i).getBlobDir()));
            }
            if (extTableDfilesArrayList.get(i).getClobDir() != null && ! "".equals(trim(extTableDfilesArrayList.get(i).getClobDir()))){
                ddl.append(";CLOBDIR:").append(trim(extTableDfilesArrayList.get(i).getClobDir()));
            }
            // 是否最后一行
            if(i==(extTableInfo.getNumDfiles()-1)){
                ddl.append("'\n  ),\n");
            } else {
                ddl.append("',\n");
            }
        }

        // FORMAT
        if ("D".equals(extTableInfo.getFormatType())){
            ddl.append("  FORMAT 'DELIMITED',\n");
        } else if ("F".equals(extTableInfo.getFormatType())){
            ddl.append("  FORMAT 'FIXED',\n");
        } else if ("I".equals(extTableInfo.getFormatType())){
            ddl.append("  FORMAT 'GBASEDBT',\n");           // gbasedbt or informix
        }
        // DELIMITER
        if (extTableInfo.getFieldDelimiter() != null){
            ddl.append("  DELIMITER '").append(extTableInfo.getFieldDelimiter()).append("',\n");
        }
        // RECORDEND
        if (extTableInfo.getRecordDelimiter() != null){
            ddl.append("  RECORDEND '").append(extTableInfo.getRecordDelimiter()).append("',\n");
        }
        // DBDATE
        if (extTableInfo.getDateFormat() != null){
            ddl.append("  DBDATE '").append(trim(extTableInfo.getDateFormat())).append("',\n");
        }
        // DBMONEY
        if (extTableInfo.getMoneyFormat() != null){
            ddl.append("  DBMONEY '").append(trim(extTableInfo.getMoneyFormat())).append("',\n");
        }
        // MAXERRORS
        if (extTableInfo.getMaxErrors() != -1 ){
            ddl.append("  MAXERRORS ").append(extTableInfo.getMaxErrors()).append(",\n");
        }
        // REJECTFILE
        if (extTableInfo.getRejectFile() != null){
            ddl.append("  REJECTFILE '").append(trim(extTableInfo.getRejectFile())).append("',\n");
        }
        // flags
        if ((extTableInfo.getFlags() & 4) == 4){
            ddl.append("  DELUXE,\n");
        } else if((extTableInfo.getFlags() & 8) == 8){
            ddl.append("  EXPRESS,\n");
        }
        if ((extTableInfo.getFlags() & 2) == 2){
            ddl.append("  ESCAPE ON\n);");
        } else {
            ddl.append("  ESCAPE OFF\n);");
        }

        return ddl.toString();
    }

    /**
     * 生成建表语句的 create table 字段部分
     * @param connection
     * @param tableInfo
     * @return
     * @throws SQLException
     */
    @Deprecated
    private static String buildTableSql(Connection connection, Table tableInfo, boolean displaysqlmode) throws SQLException {
        String parttern_constraint = "^[cur]\\d+_\\d+";              // u=unique,r=reference,c=check
        String sqlmode = tableInfo.getTableSqlMode();
        StringBuilder ddl = new StringBuilder();
        if (displaysqlmode){
            ddl.append("SET ENVIRONMENT SQLMODE '").append(sqlmode).append("';\n");
        }
        ddl.append("CREATE ");
        // global temporary
        if (! "".equals(tableInfo.getTableGlobalTemporary())){
            ddl.append(tableInfo.getTableGlobalTemporary()).append(" ");
        }
        // external & raw
        ddl.append(tableInfo.getTableFlag()).append("TABLE ");

        ddl.append(getName(tableInfo.getName(),sqlmode)).append(" (\n");

        ArrayList<CheckInfo> checkInfoArrayList = getCheck(connection,tableInfo.getName());
        ArrayList<ColumnsInfo> columnsInfoArrayList = getColInfo(connection,tableInfo);
        ArrayList<PrimaryKeyInfo> primaryKeyInfoArrayList = getPrimarykey(connection,columnsInfoArrayList,tableInfo.getName(),sqlmode);

        // 按顺序处理各个类型
        for (int i=0;i<columnsInfoArrayList.size();i++){
            // 字段名
            ddl.append("  ").append(getName(columnsInfoArrayList.get(i).getColName(),sqlmode));
            // 字段类型
            ddl.append(" ").append(getColTypeName(columnsInfoArrayList.get(i).getColType(),
                columnsInfoArrayList.get(i).getColLength()));            
            // 默认值，依据ColDefType处理
            if (columnsInfoArrayList.get(i).getColDefType() != null){
                ddl.append(" DEFAULT ").append(getDefaults(columnsInfoArrayList.get(i).getColType(),
                    columnsInfoArrayList.get(i).getColDefType(),
                    columnsInfoArrayList.get(i).getColDef()));
            }
            // 是否为 NOT NULL
            if (! columnsInfoArrayList.get(i).isIsNullable()){
                ddl.append(" NOT NULL");
            }
            // 非最后一个字段，后面加上 ,\n
            if (i<columnsInfoArrayList.size()-1){
                ddl.append(",\n");
            }
        }
        // 检查约束
        if (checkInfoArrayList.size() > 0){
            for(int i=0;i<checkInfoArrayList.size();i++){
                // Oracle模式下，constraint在前
                if ("Oracle".equalsIgnoreCase(sqlmode)){
                    if (!Pattern.matches(parttern_constraint, checkInfoArrayList.get(i).getConstrName())) {
                        ddl.append(",\n  CONSTRAINT ").append(getName(checkInfoArrayList.get(i).getConstrName()));
                        ddl.append("  CHECK ").append(checkInfoArrayList.get(i).getCheckText());
                    } else {
                        ddl.append(",\n  CHECK ").append(checkInfoArrayList.get(i).getCheckText());
                    }
                    // default GBase模式
                } else {
                    ddl.append(",\n  CHECK ").append(checkInfoArrayList.get(i).getCheckText());
                    if (!Pattern.matches(parttern_constraint, checkInfoArrayList.get(i).getConstrName())) {
                        ddl.append(" CONSTRAINT ").append(getName(checkInfoArrayList.get(i).getConstrName()));
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
                        ddl.append(",\n  CONSTRAINT ").append(getName(primaryKeyInfoArrayList.get(i).getConstrName()));
                        if ("P".equals(primaryKeyInfoArrayList.get(i).getConstrType())) {
                            ddl.append("  PRIMARY KEY(");
                        } else if ("U".equals(primaryKeyInfoArrayList.get(i).getConstrType())) {
                            ddl.append("  UNIQUE(");
                        }
                        ddl.append(primaryKeyInfoArrayList.get(i).getIdxCols()).append(")");
                    } else {
                        if ("P".equals(primaryKeyInfoArrayList.get(i).getConstrType())) {
                            ddl.append(",\n  PRIMARY KEY(");
                        } else if ("U".equals(primaryKeyInfoArrayList.get(i).getConstrType())) {
                            ddl.append(",\n  UNIQUE(");
                        }
                        ddl.append(primaryKeyInfoArrayList.get(i).getIdxCols()).append(")");
                    }
                    // default GBase模式
                } else {
                    if ("P".equals(primaryKeyInfoArrayList.get(i).getConstrType())) {
                        ddl.append(",\n  PRIMARY KEY(");
                    } else if ("U".equals(primaryKeyInfoArrayList.get(i).getConstrType())) {
                        ddl.append(",\n  UNIQUE(");
                    }
                    ddl.append(primaryKeyInfoArrayList.get(i).getIdxCols()).append(")");
                    if (!Pattern.matches(parttern_constraint, primaryKeyInfoArrayList.get(i).getConstrName())) {
                        ddl.append("  CONSTRAINT ").append(getName(primaryKeyInfoArrayList.get(i).getConstrName()));
                    }
                }
            }
        }
        ddl.append("\n) ");
        return ddl.toString();
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
    private static ArrayList<PrimaryKeyInfo> getPrimarykey(Connection connection,ArrayList<ColumnsInfo> arrayList,String tablename, String sqlmode) throws SQLException {
        ArrayList<PrimaryKeyInfo> primaryKeyArrayList = new ArrayList<>();
        String sqlstr = """
            SELECT con.constrname,con.constrtype,idx.indexkeys::lvarchar as idxcols 
            FROM sysconstraints con, sysindices idx, systables t 
            WHERE con.idxname = idx.idxname 
            AND con.tabid = t.tabid 
            AND t.tabname = ? 
            AND con.constrtype in ('P','U')
            AND LEFT(con.constrname,1) != ' ';
            """;
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlstr)) {
            preparedStatement.setString(1,tablename);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()){
                    PrimaryKeyInfo primaryKeyInfo = new PrimaryKeyInfo();
                    primaryKeyInfo.setIdxCols(getIdxCols(connection, resultSet.getString("idxcols"),getColNameListByColumnsInfo(arrayList),sqlmode));
                    primaryKeyInfo.setConstrName(resultSet.getString("constrname"));
                    primaryKeyInfo.setConstrType(resultSet.getString("constrtype"));
                    primaryKeyArrayList.add(primaryKeyInfo);
                }
            }
        }
        return primaryKeyArrayList;
    }

    //added by L3 20260124
    @Override
    public String printPackage(Connection connection, String procname) throws SQLException {
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
