package com.dbboys.infra.db;

import com.dbboys.model.ConnectFolder;
import com.dbboys.model.Connect;
import com.dbboys.model.UpdateResult;
import com.dbboys.core.ConnectionService;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public  class LocalDbRepository {
    private static final Logger log = LogManager.getLogger(LocalDbRepository.class);

    /** @deprecated Use {@link com.dbboys.infra.db.LocalDbConnection#get()} */
    @Deprecated
    private static Connection conn = com.dbboys.infra.db.LocalDbConnection.get();

    //初始化数据库，在恢复出厂设置时调用
    public static boolean initDB()   {
        boolean success = false;
        try {
            Statement statement = null;
            statement = conn.createStatement();
            statement.executeUpdate("drop table if exists t_connect");
            statement.executeUpdate("drop table if exists t_connect_folder");
            statement.executeUpdate("drop table if exists t_sqlhistory");
            statement.executeUpdate("create table if not exists t_connect_folder(c_id INTEGER PRIMARY KEY AUTOINCREMENT,c_name varchar(100),c_expand int)");
            statement.executeUpdate("create table if not exists t_connect(c_id INTEGER PRIMARY KEY AUTOINCREMENT,c_parentid int,c_name varchar(100),c_dbtype varchar(50),c_dbversion varchar(100),c_driver varchar(100),c_drivermd5 varchar(100),c_ip varchar(50),c_port varchar(50),c_database varchar(100),c_readonly varchar(2),c_username varchar(50),c_password varchar(50),c_props varchar(3200),c_info varchar(3200),c_ssh_host varchar(100),c_ssh_port varchar(10),c_ssh_user varchar(100),c_ssh_password varchar(100),c_ssh_enabled varchar(2))");
            statement.executeUpdate("create table if not exists t_sqlhistory(c_connectid INTEGER,c_database varchar(50),c_sql varchar(32000),c_starttime varchar(20),c_endtime varchar(20),c_elapsedtime varchar(20),c_affect int,c_mark varchar(100))");
            statement.executeUpdate("INSERT INTO t_connect_folder(c_name,c_expand) VALUES ('数据库连接分类[1级系统]',1)");
            //statement.executeUpdate("INSERT INTO t_connect(c_parentid,c_level,c_name,c_expand,c_dbtype,c_ip,c_port,c_username,c_password) VALUES (2,3, '核心业务系统',0,'GBASE 8S','192.168.17.123','9088','gbasedbt','GBase123')");
            success = true;
        }catch (Exception e) {
            log.error("SQLite operation failed", e);
        }
        return success;
    }

    //获取系统分类
    public static List<ConnectFolder> getConnectFolders()  {
        List<ConnectFolder> connectFolders = new ArrayList<>();
        Statement statement = null;
        try {
            statement = conn.createStatement();
            ResultSet rs = statement.executeQuery("select c_id,c_name,c_expand from t_connect_folder order by c_name");
            while (rs.next()) {
                ConnectFolder connectFolder = new ConnectFolder();
                connectFolder.setId(rs.getInt(1));
                connectFolder.setName(rs.getString(2));
                connectFolder.setExpand(rs.getInt(3));
                connectFolders.add(connectFolder);
            }
        }catch (Exception e) {
            log.error("SQLite operation failed", e);
        }
        return connectFolders;
    }

    //清除历史记录
    public static boolean deleteSqlHistory()   {
        boolean success = false;
        try {
            PreparedStatement psmt = null;
            psmt=conn.prepareStatement("""
                    DELETE FROM t_sqlhistory
                    WHERE (c_connectid, c_endtime) NOT IN (
                      SELECT c_connectid, c_endtime
                      FROM (
                        SELECT
                          c_connectid,
                          c_endtime,
                          ROW_NUMBER() OVER (PARTITION BY c_connectid ORDER BY c_endtime DESC) AS rn
                        FROM t_sqlhistory
                      ) AS ranked
                      WHERE rn <= 1000)
""");
            psmt.executeUpdate();
            success = true;
        }catch (Exception e) {
            log.error("SQLite operation failed", e);
        }
        return success;
    }

    //创建系统分类
    public static boolean createConnectFolder(ConnectFolder connectFolder)   {
        boolean success = false;
        try {
            PreparedStatement psmt = null;
            psmt=conn.prepareStatement("insert into t_connect_folder(c_name,c_expand) values(?,?)");
            psmt.setObject(1,connectFolder.getName());
            psmt.setObject(2,connectFolder.getExpand());
            psmt.executeUpdate();
            //给新增的分类设置ID，避免新增分类因为没有ID，无法显示新建在该分类下的连接
            psmt=conn.prepareStatement("select max(c_id) from t_connect_folder");
            ResultSet rs=psmt.executeQuery();
            while (rs.next()) {
                connectFolder.setId(rs.getInt(1)); //获取到ID赋值给connectFolder对象
            }
            rs.close();
            success = true;
        }catch (Exception e) {
            log.error("SQLite operation failed", e);
        }
        return success;
    }

    //更新系统分类
    public static boolean updateConnectFolder(ConnectFolder connectFolder)   {
        boolean success = false;
        try {
            PreparedStatement psmt = null;
            psmt=conn.prepareStatement("update t_connect_folder set c_name=?,c_expand=? where c_id=?");
            psmt.setObject(1,connectFolder.getName());
            psmt.setObject(2,connectFolder.getExpand());
            psmt.setObject(3,connectFolder.getId());
            psmt.executeUpdate();
            success = true;
        }catch (Exception e) {
            log.error("SQLite operation failed", e);
        }
        return success;
    }


    //删除系统分类
    public static boolean deleteConnectFolder(ConnectFolder connectFolder)   {
        boolean success = false;
        try {
            PreparedStatement psmt = null;
            psmt=conn.prepareStatement("delete from t_connect_folder where c_id=?");
            psmt.setObject(1,connectFolder.getId());
            psmt.executeUpdate();
            success = true;
        }catch (Exception e) {
            log.error("SQLite operation failed", e);
        }
        return success;
    }

    //获取系统分类
    //获取connect对象分类
    public static String getConnectType(Connect connect)  {
        String connectFolderName = null;
        PreparedStatement psmt ;
        try {
            psmt= conn.prepareStatement("select c_name from t_connect_folder where c_id=?");
            psmt.setInt(1, connect.getParentId());
            ResultSet rs=psmt.executeQuery();
            while(rs.next()){
                connectFolderName=rs.getString(1);
            }
            rs.close();
            psmt.close();
        } catch (SQLException e) {
            log.error("SQLite operation failed", e);
        }
        return connectFolderName;
    }

    //检查连接名是否已存在
    public static boolean checkConnectLeafNameExists(Connect connect)  {
        Boolean nameExists = false;
        PreparedStatement psmt = null;
        try {
            psmt= conn.prepareStatement("select c_name from t_connect where c_name=? and c_id!=?");
            psmt.setString(1, connect.getName());
            psmt.setInt(2, connect.getId());
            ResultSet rs=psmt.executeQuery();
            while(rs.next()){
                nameExists=true;
            }
            rs.close();
            psmt.close();
        } catch (SQLException e) {
            System.out.println(e);
        }
        return nameExists;
    }

    //创建连接
    //创建连接，如果错误返回错误信息，在调用出弹出提示
    public static String createConnectLeaf(Connect connect)  {
        String result = "";
        PreparedStatement psmt = null;
        try {
            com.dbboys.app.AppContext.get(ConnectionService.class).setConnectInfo(connect);
            psmt=conn.prepareStatement("insert into t_connect(c_parentid,c_name,c_dbtype,c_driver,c_ip,c_port,c_database,c_readonly,c_username,c_password,c_props,c_info,c_drivermd5,c_dbversion,c_ssh_host,c_ssh_port,c_ssh_user,c_ssh_password,c_ssh_enabled) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
            psmt.setObject(1, connect.getParentId());
            psmt.setObject(2, connect.getName());
            psmt.setObject(3, connect.getDbtype());
            psmt.setObject(4, connect.getDriver());
            psmt.setObject(5, connect.getIp());
            psmt.setObject(6, connect.getPort());
            psmt.setObject(7, connect.getCatalog());
            psmt.setObject(8, connect.getReadonly()?"1":"0");
            psmt.setObject(9, connect.getUsername());
            psmt.setObject(10, connect.getPassword());
            psmt.setObject(11, connect.getProps());
            psmt.setObject(12, connect.getInfo());
            psmt.setObject(13, connect.getDrivermd5());
            psmt.setObject(14, connect.getDbversion());
            psmt.setObject(15, connect.getSshHost());
            psmt.setObject(16, connect.getSshPort());
            psmt.setObject(17, connect.getSshUser());
            psmt.setObject(18, connect.getSshPassword());
            psmt.setObject(19, connect.getSshEnabled()?"1":"0");

            psmt.executeUpdate();
            psmt=conn.prepareStatement("select max(c_id) from t_connect");
            ResultSet rs=psmt.executeQuery();
            while (rs.next()) {
                connect.setId(rs.getInt(1)); //获取到ID赋值给connect对象
            }
            rs.close();
            psmt.close();
        } catch (Exception e) {
            log.error("SQLite operation failed", e);
            result=e.getMessage();
        }
        return result;
    }

    //删除连接
    public static boolean deleteConnectLeaf(Connect connect)  {
        boolean success = false;
        PreparedStatement psmt = null;
        try {
            psmt= conn.prepareStatement("delete from t_connect where c_id=?");
            psmt.setInt(1, connect.getId());
            psmt.executeUpdate();
            psmt= conn.prepareStatement("delete from t_sqlhistory where c_connectid=?");
            psmt.setInt(1, connect.getId());
            psmt.executeUpdate();
            success = true;
        } catch (SQLException e) {
            log.error("SQLite operation failed", e);
        }
        return success;
    }

    //检查驱动是否有连接在使用
    public static boolean checkDriverInUse(String jarName)  {
        Boolean success = false;
        PreparedStatement psmt = null;
        try {
            psmt= conn.prepareStatement("select 1 from t_connect where c_driver=?");
            psmt.setString(1,jarName);
            ResultSet rs=psmt.executeQuery();
            while(rs.next()){
                success=true;
            }
            rs.close();
            psmt.close();
        } catch (SQLException e) {
            log.error("SQLite operation failed", e);
        }
        return success;
    }

    //获取所有连接
    public static List<Connect> getConnectLeafs()  {
        List<Connect> connectLeafList = new ArrayList<>();
        Statement statement = null;
        try{
            statement = conn.createStatement();
            ResultSet rs=statement.executeQuery("select c_id,c_parentid,c_name,c_ip,c_port,c_database,c_username,c_dbtype,c_password,c_driver,c_props,c_info,c_drivermd5,c_dbversion,c_readonly from t_connect order by c_name");
            while (rs.next()){
                Connect connect =new Connect();
                connect.setId(rs.getInt(1));
                connect.setParentId(rs.getInt(2));
                connect.setName(rs.getString(3));
                connect.setIp(rs.getString(4));
                connect.setPort(rs.getString(5));
                connect.setCatalog(rs.getString(6));
                connect.setUsername(rs.getString(7));
                connect.setDbtype(rs.getString(8));
                connect.setPassword(rs.getString(9));
                connect.setDriver(rs.getString(10));
                connect.setProps(rs.getString(11));
                connect.setInfo(rs.getString(12));
                connect.setDrivermd5(rs.getString(13));
                connect.setDbversion(rs.getString(14));
                connect.setReadonly(Boolean.valueOf(String.valueOf(rs.getString(15).equals("1")?true:false)));
                // SSH columns may not exist yet (migration done in initDB)
                try { connect.setSshHost(rs.getString(16)); } catch (Exception ignored) {}
                try { connect.setSshPort(rs.getString(17)); } catch (Exception ignored) {}
                try { connect.setSshUser(rs.getString(18)); } catch (Exception ignored) {}
                try { connect.setSshPassword(rs.getString(19)); } catch (Exception ignored) {}
                try { connect.setSshEnabled(rs.getString(20) != null && rs.getString(20).equals("1")); } catch (Exception ignored) {}
                connectLeafList.add(connect);
            }
        } catch (Exception e) {
            log.error("SQLite operation failed", e);
        }
        return connectLeafList;
    }




        //获取分类的所有连接
        public static List<Connect> getFolderConnect(ConnectFolder connectParm)  {
            List<Connect> connectLeafList = new ArrayList<>();
            PreparedStatement psmt = null;
            try{
                psmt= conn.prepareStatement("select c_id,c_parentid,c_name,c_dbtype,c_ip,c_port,c_database,c_readonly,c_username,c_password,c_driver,c_props,c_info,c_drivermd5,c_dbversion from t_connect where c_parentid=? order by c_name");
                psmt.setInt(1,connectParm.getId());
                ResultSet rs=psmt.executeQuery();
                while (rs.next()){
                    Connect connect=new Connect();
                    connect.setId(rs.getInt(1));
                    connect.setParentId(rs.getInt(2));
                    connect.setName(rs.getString(3));
                    connect.setDbtype(rs.getString(4));
                    connect.setIp(rs.getString(5));
                    connect.setPort(rs.getString(6));
                    connect.setCatalog(rs.getString(7));
                    connect.setReadonly(rs.getString(8).equals("1")?true:false);
                    connect.setUsername(rs.getString(9));
                    connect.setPassword(rs.getString(10));
                    connect.setDriver(rs.getString(11));
                    connect.setProps(rs.getString(12));
                    connect.setInfo(rs.getString(13));
                    connect.setDrivermd5(rs.getString(14));
                    connect.setDbversion(rs.getString(15));
                    connectLeafList.add(connect);
                }
            } catch (Exception e) {
                log.error("SQLite operation failed", e);
            }
            return connectLeafList;
        }

    /*
           //获取根节点
           public static Connect getRootSystemLevel()  {
               Connect return_sl=new Connect();
               Statement statement = null;
               try {
                   statement = conn.createStatement();
                   ResultSet rs=statement.executeQuery("select c_id,c_parentid,c_level,c_name,c_expand from t_connect where c_id=1");
                   while (rs.next()){
                       return_sl.setId(rs.getInt(1));
                       return_sl.setParentId(rs.getInt(2));
                       return_sl.setLevel(rs.getInt(3));
                       return_sl.setName(rs.getString(4));
                       return_sl.setExpand(rs.getInt(5));
                   }
               } catch (Exception e) {
                   log.error("SQLite operation failed", e);
               }
               return return_sl;
           }
*/
           //跟新连接信息，重命名，默认展开，移动等地方调用
           public static boolean updateConnect(Connect connect)  {
               boolean success = false;
               PreparedStatement psmt = null;
               try {
                   psmt= conn.prepareStatement("update t_connect set c_parentid=?,c_name=?,c_database=?,c_props=?,c_ssh_host=?,c_ssh_port=?,c_ssh_user=?,c_ssh_password=?,c_ssh_enabled=? where c_id=?");
                   psmt.setInt(1,connect.getParentId());
                   psmt.setString(2,connect.getName());
                   psmt.setString(3,connect.getCatalog());
                   psmt.setString(4,connect.getProps());
                   psmt.setString(5,connect.getSshHost());
                   psmt.setString(6,connect.getSshPort());
                   psmt.setString(7,connect.getSshUser());
                   psmt.setString(8,connect.getSshPassword());
                   psmt.setString(9,connect.getSshEnabled()?"1":"0");
                   psmt.setInt(10,connect.getId());
                   psmt.executeUpdate();
                   success = true;
               } catch (Exception e) {
                   log.error("SQLite operation failed", e);
               }
               return success;
           }

/*
           //删除连接
           public static boolean deleteConnect(Connect connect)  {
               boolean return_val = false;
               PreparedStatement psmt = null;
               try {
                   psmt= conn.prepareStatement("delete from t_connect where c_id=?");
                   psmt.setInt(1,connect.getId());
                   psmt.executeUpdate();
                   psmt= conn.prepareStatement("delete from t_sqlhistory where c_connectid=?");
                   psmt.setInt(1,connect.getId());
                   psmt.executeUpdate();
                   if(connect.getLevel()==2){
                       psmt= conn.prepareStatement("delete from t_connect where c_parentid=?");
                       psmt.setInt(1,connect.getId());
                       psmt.executeUpdate();
                   }
                   return_val = true;
               } catch (SQLException e) {
                   System.out.println(e);
               }
               return return_val;
           }


           //获取connect对象分类
           public static String getConnectType(Connect connect)  {
               String return_val = null;
               PreparedStatement psmt = null;
               try {
                   psmt= conn.prepareStatement("select c_name from t_connect where c_id=?");
                   psmt.setInt(1,connect.getParentId());
                   ResultSet rs=psmt.executeQuery();
                   while(rs.next()){
                       return_val=rs.getString(1);
                   }
                   rs.close();
                   psmt.close();
               } catch (SQLException e) {
                   System.out.println(e);
               }
               return return_val;
           }

           //检查连接名是否已存在
           public static boolean checkNameExists(Connect connect)  {
               Boolean return_val = false;
               PreparedStatement psmt = null;
               try {
                   psmt= conn.prepareStatement("select c_name from t_connect where c_name=? and c_id!=?");
                   psmt.setString(1,connect.getName());
                   psmt.setInt(2,connect.getId());
                   ResultSet rs=psmt.executeQuery();
                   while(rs.next()){
                       return_val=true;
                   }
                   rs.close();
                   psmt.close();
               } catch (SQLException e) {
                   System.out.println(e);
               }
               return return_val;
           }


*/
           //检查连接名是否已存在
           public static String getCopyName(Connect connect)  {
               String return_val = null;
               PreparedStatement psmt = null;
               List<String> name_list =new ArrayList<String>();
               try {
                   psmt= conn.prepareStatement("select c_name from t_connect");
                   ResultSet rs=psmt.executeQuery();
                   while(rs.next()){
                       name_list.add(rs.getString(1));
                   }
                   rs.close();
                   psmt.close();
               } catch (SQLException e) {
                   System.out.println(e);
               }
               int i=1;
               while(true){
                   return_val=connect.getName()+"_"+i;
                   if(name_list.contains(return_val)){
                       i=i+1;
                   }else{
                       break;
                   }
               }
               return return_val;
           }
           /*
           //创建连接，如果错误返回错误信息，在调用出弹出提示
           public static String createConnect(Connect connect)  {
               String return_val = "";
               PreparedStatement psmt = null;
               Connection connection=null;
               try {
                   if(connect.getIp()!=null){ //如果是增加系统分类，没有IP,也不需要测试连接
                        // connection = AppContext.get(ConnectionService.class).createConnection(connect);

                       //查询数据库信息，包括版本，启动环境，系统配置
                       ResultSet rs=null;
                       String dbversion=null;
                       if(connect.getUsername().equals("gbasedbt")){
                           //connection.createStatement().executeUpdate("set environment sqlmode 'gbase'");
                           rs=connection.createStatement().executeQuery("EXECUTE FUNCTION sysadmin:task('onstat','-V');");
                           rs.next();
                           dbversion=rs.getString(1).replace("GBase Database Server Version 12.10.FC4G1","").replace(" Software Serial Number AAA#B000000","");
                           if(!dbversion.contains("GBase8s")){
                               DatabaseMetaData metaData = connection.getMetaData();
                               String databaseProductVersion = metaData.getDatabaseProductVersion();
                               dbversion="GBase8sV"+databaseProductVersion+"_"+dbversion;
                           }
                       }else{
                           dbversion="当前用户无权限获取版本信息，请使用gbasedbt用户连接获取\n";
                       }
                       connect.setDbversion(dbversion); //保存数据库版本,最后有换行
                       String info="##########################################################################################\n";
                       info+="Instance Boot Information\n";
                       info+="##########################################################################################\n";
                       //rs=connection.createStatement().executeQuery("select env_name,case upper(trim(env_value)) when 'ZH_CN.GB18030-2000' then 'zh_CN.5488' when 'ZH_CN.UTF8' then 'zh_CN.57372' else trim(env_value) end from sysmaster:sysenv");
                       rs=connection.createStatement().executeQuery("select env_name,trim(env_value) from sysmaster:sysenv");

                       while(rs.next()){
                           info+=String.format("%-30s",rs.getString(1))+rs.getString(2)+"\n";
                           if(rs.getString(1).equals("DB_LOCALE")){
                               //if(connect.getDatabase().equals("gbasedbt")||connect.getDatabase().equals("sys")||connect.getDatabase().equals("sysadmin")||connect.getDatabase().equals("sysmaster")||connect.getDatabase().equals("sysutils")||connect.getDatabase().equals("syscdcv1")){
                                   //如果连接默认库是系统表，不设置DB_LOCALE
                               //}else{
                                   //编辑连接属性propName在前
                                   connect.setProps(connect.getProps().replace("{\"propValue\":\"\",\"propName\":\"DB_LOCALE\"}","{\"propValue\":\""+rs.getString(2).toUpperCase().trim().replace("ZH_CN.GB18030-2000","zh_CN.5488").replace("ZH_CN.UTF8","zh_CN.57372")+"\",\"propName\":\"DB_LOCALE\"}"));
                                   //新连接顺序propName在前
                                   connect.setProps(connect.getProps().replace("{\"propName\":\"DB_LOCALE\",\"propValue\":\"\"}","{\"propName\":\"DB_LOCALE\",\"propValue\":\""+rs.getString(2).toUpperCase().trim().replace("ZH_CN.GB18030-2000","zh_CN.5488").replace("ZH_CN.UTF8","zh_CN.57372")+"\"}"));
                               //}

                           }
                       };
                       info+="\n##########################################################################################\n";
                       info+="System Information\n";
                       info+="##########################################################################################\n";
                       rs=connection.createStatement().executeQuery("SELECT * from sysmaster:sysmachineinfo ");
                       rs.next();
                       for(int i=1;i<=24;i++){
                           info+=String.format("%-30s",rs.getMetaData().getColumnName(i));
                           info+=rs.getString(i)+"\n";
                       }

                       connect.setInfo(info);

                       //设置连接驱动的MD5码
                       connect.setDrivermd5(new MD5Util().getMD5Checksum(Paths.get("extlib/"+connect.getDbtype()+"/"+connect.getDriver()).toFile().getAbsolutePath()));

                   }
                   psmt=conn.prepareStatement("insert into t_connect(c_parentid,c_level,c_expand,c_name,c_dbtype,c_driver,c_ip,c_port,c_database,c_readonly,c_username,c_password,c_props,c_info,c_drivermd5,c_dbversion) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
                   psmt.setObject(1,connect.getParentId());
                   psmt.setObject(2,connect.getLevel());
                   psmt.setObject(3,connect.getExpand());
                   psmt.setObject(4,connect.getName());
                   psmt.setObject(5,connect.getDbtype());
                   psmt.setObject(6,connect.getDriver());
                   psmt.setObject(7,connect.getIp());
                   psmt.setObject(8,connect.getPort());
                   psmt.setObject(9,connect.getCatalog());
                   psmt.setObject(10,connect.getReadonly());
                   psmt.setObject(11,connect.getUsername());
                   psmt.setObject(12,connect.getPassword());
                   psmt.setObject(13,connect.getProps());
                   psmt.setObject(14,connect.getInfo());
                   psmt.setObject(15,connect.getDrivermd5());
                   psmt.setObject(16,connect.getDbversion());

                   psmt.executeUpdate();
                   psmt=conn.prepareStatement("select max(c_id) from t_connect");
                   ResultSet rs=psmt.executeQuery();
                   while (rs.next()) {
                       connect.setId(rs.getInt(1)); //获取到ID赋值给connect对象
                   }
                   rs.close();
                   psmt.close();
                   if(connection!=null){
                       connection.close(); //测试连接完成后关闭释放
                   }
               } catch (Exception e) {
                   log.error("SQLite operation failed", e);
                   return_val=e.getMessage();
               }
               return return_val;
           }

   */
    //创建连接，如果错误返回错误信息，在调用出弹出提示
    public static String saveSqlHistory(UpdateResult updateResult)  {
        String return_val = "";
        PreparedStatement psmt = null;
        try {
            psmt=conn.prepareStatement("insert into t_sqlhistory values(?,?,?,?,?,?,?,?)");
            psmt.setObject(1,updateResult.getConnectId());
            psmt.setObject(2,updateResult.getDatabase());
            psmt.setObject(3,updateResult.getUpdateSql());
            psmt.setObject(4,updateResult.getStartTime());
            psmt.setObject(5,updateResult.getEndTime());
            psmt.setObject(6,updateResult.getElapsedTime());
            psmt.setObject(7,updateResult.getAffectedRows());
            psmt.setObject(8,updateResult.getMark());
            psmt.executeUpdate();
            psmt.close();
        } catch (Exception e) {
            log.error("SQLite operation failed", e);
            return_val=e.getMessage();
        }
        return return_val;
    }



    //创建连接，如果错误返回错误信息，在调用出弹出提示
    public static List<UpdateResult> getSqlHistoryList(Integer Id)  {
        List<UpdateResult> return_val = new ArrayList<>();
        PreparedStatement psmt = null;
        ResultSet rs = null;
        UpdateResult updateResult=new UpdateResult();
        try {
            psmt=conn.prepareStatement("select * from t_sqlhistory where c_connectid=?");
            psmt.setObject(1,Id);
            rs=psmt.executeQuery();
            while (rs.next()) {
                updateResult=new UpdateResult();
                updateResult.setDatabase(rs.getString(2));
                updateResult.setUpdateSql(rs.getString(3));
                updateResult.setStartTime(rs.getString(4));
                updateResult.setEndTime(rs.getString(5));
                updateResult.setElapsedTime(rs.getString(6));
                updateResult.setAffectedRows(rs.getInt(7));
                updateResult.setMark(rs.getString(8));
                return_val.add(updateResult);
            }
            rs.close();
            psmt.close();
        } catch (Exception e) {
            log.error("SQLite operation failed", e);
        }
        return return_val;
    }


}
