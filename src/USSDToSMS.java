import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;


public class USSDToSMS extends  TimerTask implements Runnable {

	static Logger logger;
	static Properties props;
	static boolean testMod = true;
	
	public static void main(String[] args) {
		
		props = getProperties();
		PropertyConfigurator.configure(props);
		logger =Logger.getLogger(USSDToSMS.class);
		logger.info("Logger Load Success!");
		
		if("false".equalsIgnoreCase(props.getProperty("testMod"))){
			testMod = false;
		}else{
			logger.info("In test mod...");
		}
		
		Long period = props.getProperty("perios")!=null && props.getProperty("perios").matches("^\\d+$")?Long.valueOf(props.getProperty("perios")):5000L;
		if(args.length>0 && args[0].matches("^\\d+$"))
			period = Long.parseLong(args[0]);

		logger.info("Execute period "+period+" millisecond");
		
		Timer timer =new Timer();
		//預設每5秒啟動一次
		timer.schedule(new USSDToSMS(),0,period );
		
	}
	public void run() {
		process();
	}
	
	public void process(){
		logger.info("program srart...");
		Connection conn = null;
		Statement st = null;
		ResultSet rs = null;
		try {
			conn = connDB();
			
			setTime();

			//更新需要處理的部分
			String sql = "UPdate USSDTOSMS Set status = 1 "
					+ "where CREATETIME >= to_date('"+preTimeS+"','yyyyMMddhh24miss') "
					+ "and CREATETIME <= to_date('"+nowTimeS+"','yyyyMMddhh24miss') and status=0 ";
			
			//撈取需要處理的資料
			String sql2 = "select SMSID,PHONENUMBER,IMSI,SMSCONTENT,CREATETIME,PROCESSTIME,STATUS "
					+ "from USSDTOSMS A "
					+ "where status = 1 ";
			
			st = conn.createStatement();
			st.execute(sql);
			
			rs = st.executeQuery(sql2);
			
			String completes = "";
			String errorSMSs = "";
			
			while(rs.next()){
				String SMSID = rs.getString("SMSID");
				String PHONENUMBER = rs.getString("PHONENUMBER");
				String SMSCONTENT = rs.getString("SMSCONTENT");
				
				try {
					sendSMS(PHONENUMBER,SMSCONTENT);//正式使用時 phoneNumber必須加上「0000」
					completes += SMSID + ",";
				} catch (IOException e) {
					errorSMSs += SMSID + ",";
					errorProccess(e);
				} catch (Exception e) {
					errorSMSs += SMSID + ",";
					errorProccess(e);
				} 
			}
			//更新已處理
			if(!"".equals(completes)){
				sql = "UPdate USSDTOSMS Set status = 2,PROCESSTIME = sysdate "
						+ "where SMSID in ("+completes.substring(0,completes.length()-1)+") ";
				logger.debug("update completed SMS : "+sql);
				st.executeUpdate(sql);
			}
			
			
			//更新錯誤資料
			if(!"".equals(errorSMSs)){
				sql = "UPdate USSDTOSMS Set status = 3,PROCESSTIME = sysdate "
						+ "where SMSID in ("+errorSMSs.substring(0,errorSMSs.length()-1)+") ";
				logger.debug("update process fail SMS : "+sql);
				st.executeUpdate(sql);
			}
			
			
			logger.info("program end...");
			lastTime = nowTime;
			
		} catch (ClassNotFoundException e) {
			errorProccess(e);
		} catch (SQLException e) {
			errorProccess(e);
		}finally{
			try {
				if(rs!=null)
					rs.close();
				if(st!=null)
					st.close();
				if(conn!=null)
					conn.close();
			} catch (SQLException e) {
				errorProccess(e);
			}
		}
		
	}
	
	public void errorProccess(Exception e){
		errorProccess("",e);
	}
	
	public void errorProccess(String msg,Exception e){
		String errorMag = "";
		if(e!=null){
			logger.error(msg, e);
			
			StringWriter s = new StringWriter();
			e.printStackTrace(new PrintWriter(s));
			//send mail
			errorMag=s.toString();
		}else{
			logger.error(msg);
		}		
		
		sendMail(msg + "\n" + errorMag);
		
	};
	public void sendSMS(String phoneNumber,String content) throws Exception{
		
		if(testMod){
			logger.info("In test mod. Set to default number.");
			phoneNumber = "886989235253";
		}else{
			phoneNumber = "+0000"+phoneNumber.replace("+", "");
		}

		String URL = "http://192.168.10.125:8800/Send%20Text%20Message.htm";
		//test
		//String URL = "http://192.168.10.100:8800/Send%20Text%20Message.htm";
		
		String param = "PhoneNumber={{number}}&Text={{content}}&charset=big5&InfoCharCounter=&PID=&DCS=&Submit=Submit";
		
		//NowSMS特殊字元轉換
		content = content	
				.replace("%", "%25")
				.replace("?", "%3F")
				.replace("\"", "%22")
				.replace("<", "%3C")
				.replace(">", "%3E")
				.replace("&", "%26")
				.replace("+", "%2B")
				.replace("#", "%23")
				.replace("*", "%2A")
				.replace("!", "%21")
				.replace(",", "%2C")
				.replace("'", "%27")
				.replace("\\", "%5C")
				.replace("=", "%3D")
				.replace("€", "%E2%82%A");
		
		param = param.replace("{{number}}", phoneNumber).replace("{{content}}", content);

		logger.info("Psot sms :"+URL+"?"+param);
		int repCode = HttpPost(URL,param,"");
		
		if(repCode != 200)
			throw new Exception("Send SMS error. (response code:"+repCode+")");
	}
	
	public int HttpPost(String url,String param,String charset) throws IOException{
		URL obj = new URL(url);
		
		if(charset!=null && !"".equals(charset))
			param=URLEncoder.encode(param, charset);
		
		
		HttpURLConnection con =  (HttpURLConnection) obj.openConnection();
 
		//add reuqest header
		//con.setRequestMethod("POST");
		//con.setRequestProperty("User-Agent", USER_AGENT);
		//con.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
 
		// Send post request
		con.setDoOutput(true);
		
		DataOutputStream wr = new DataOutputStream(con.getOutputStream());
		wr.writeBytes(param);
		wr.flush();
		wr.close();
 
		int responseCode = con.getResponseCode();
		System.out.println("\nSending 'POST' request to URL : " + url);
		System.out.println("Post parameters : " + new String(param.getBytes("ISO8859-1")));
		System.out.println("Response Code : " + responseCode);
 
		BufferedReader in = new BufferedReader(
		        new InputStreamReader(con.getInputStream()));
		String inputLine;
		StringBuffer response = new StringBuffer();
 
		while ((inputLine = in.readLine()) != null) {
			response.append(inputLine);
		}
		in.close();
 
		//print result
		return responseCode;
	}
	
	
	String nowTimeS,preTimeS;
	Date nowTime,lastTime,preTime;
	
	private boolean setTime(){
		
		boolean result = true;
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
		//提前1分鐘
		nowTime=new Date(new Date().getTime()-1*60*1000);
		nowTimeS = sdf.format(nowTime);
		if(lastTime==null){
			try {
				preTime=sdf.parse("20160331101709");
			} catch (ParseException e) {
				StringWriter s = new StringWriter();
				e.printStackTrace(new PrintWriter(s));
				logger.debug("ParseException Error!",e);
				//sendMail
				sendMail("cParseException Error "+s);
				result = false;
			}
		}else{
			preTime=lastTime;
		}
		
		preTimeS = sdf.format(preTime);
		
		logger.info("Proccess from "+preTime+" to "+nowTime);
		return result;
	}
	
	private void sendMail(String msg){
		String ip ="";
		try {
			ip = InetAddress.getLocalHost().getHostAddress();
		} catch (UnknownHostException e) {
		}
		
		msg=msg+"\n from location "+"192.168.10.199 at "+new Date() ;			
		
		String [] cmd=new String[3];
		cmd[0]="/bin/bash";
		cmd[1]="-c";
		cmd[2]= "/bin/echo \""+msg+"\" | /bin/mail -s \"USSD to SMS alert\" -r  USSDTOSMS_ALERT_MAIL "+props.getProperty("mail.Receiver")+"." ;

		try{
			Process p = Runtime.getRuntime().exec (cmd);
			p.waitFor();
			logger.info("send mail :"+ cmd[2]);
		}catch (Exception e){
			logger.error("send mail fail:" + cmd[2]);
		}
	}
	
	public static Properties getProperties() {
		Properties result = new Properties();

		result.put("log4j.rootCategory", "DEBUG, stdout, FileOutput");

		result.put("log4j.appender.stdout", "org.apache.log4j.ConsoleAppender");
		result.put("log4j.appender.stdout.layout", "org.apache.log4j.PatternLayout");
		result.put("log4j.appender.stdout.layout.ConversionPattern", "%d [%5p] (%F:%L) - %m%n");

		result.put("log4j.appender.FileOutput", "org.apache.log4j.DailyRollingFileAppender");
		result.put("log4j.appender.FileOutput.layout", "org.apache.log4j.PatternLayout");
		result.put("log4j.appender.FileOutput.layout.ConversionPattern", "%d [%5p] (%F:%L) - %m%n");
		result.put("log4j.appender.FileOutput.DatePattern", "'.'yyyyMMdd");
		result.put("log4j.appender.FileOutput.File", "USSDtoSMS.log");
		
		result.put("testMod", "false");
		result.put("perios", "5000");
		result.put("mail.Receiver", "Douglas.Chuang@sim2travel.com,yvonne.lin@sim2travel.com,ranger.kao@sim2travel.com");
		
		return result;
	}
	
	public Connection connDB() throws ClassNotFoundException, SQLException {
		Connection conn = null;

			Class.forName("oracle.jdbc.driver.OracleDriver");
			conn = DriverManager.getConnection("jdbc:oracle:thin:@10.42.1.10:1521:orcl", "wacos", "oss");
			//conn = DriverManager.getConnection("jdbc:oracle:thin:@10.42.1.101:1521:s2tbs", "s2tbsadm", "s2tbsadm");
		return conn;
	}

}
