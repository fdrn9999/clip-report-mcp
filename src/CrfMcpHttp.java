import com.sun.net.httpserver.*;
import org.json.simple.*;
import org.json.simple.parser.JSONParser;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Remote MCP server over Streamable HTTP (POST /mcp, JSON-RPC). JDK HttpServer only, no deps.
 *  Reuses CrfMcpServer's tool logic. Run: java ... CrfMcpHttp [port] [host]
 *  Optional auth: env CLIP_MCP_TOKEN -> require "Authorization: Bearer <token>". */
public class CrfMcpHttp {
  static final JSONParser P = new JSONParser();
  static String TOKEN = System.getenv("CLIP_MCP_TOKEN");

  public static void main(String[] a) throws Exception {
    int port = a.length>0 ? Integer.parseInt(a[0]) : 3333;
    String host = a.length>1 ? a[1] : "127.0.0.1";
    HttpServer s = HttpServer.create(new InetSocketAddress(host, port), 0);
    s.setExecutor(Executors.newSingleThreadExecutor());   // serialize (engine + System.out redirect not thread-safe)
    s.createContext("/mcp", CrfMcpHttp::handle);
    s.start();
    System.err.println("[clip-report-mcp-http] listening on http://"+host+":"+port+"/mcp  auth="+(TOKEN!=null?"on":"off"));
  }

  static void handle(HttpExchange ex) throws IOException {
    try{
      String origin = ex.getRequestHeaders().getFirst("Origin");
      Headers h = ex.getResponseHeaders();
      h.add("Access-Control-Allow-Origin", origin!=null?origin:"*");
      h.add("Access-Control-Allow-Headers", "Content-Type, Authorization, Mcp-Session-Id");
      h.add("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
      String m = ex.getRequestMethod();
      if(m.equals("OPTIONS")){ ex.sendResponseHeaders(204,-1); ex.close(); return; }
      if(TOKEN!=null){ String auth=ex.getRequestHeaders().getFirst("Authorization");
        if(auth==null || !auth.equals("Bearer "+TOKEN)){ send(ex,401,"application/json","{\"error\":\"unauthorized\"}"); return; } }
      if(m.equals("GET")){ ex.sendResponseHeaders(405,-1); ex.close(); return; }  // no server-initiated SSE stream
      if(!m.equals("POST")){ ex.sendResponseHeaders(405,-1); ex.close(); return; }

      String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
      Object parsed = P.parse(body);
      String out;
      if(parsed instanceof JSONArray){ JSONArray batch=(JSONArray)parsed; JSONArray res=new JSONArray();
        for(Object o:batch){ JSONObject r=dispatch((JSONObject)o); if(r!=null) res.add(r); }
        out = res.isEmpty()? null : res.toJSONString();
      } else { JSONObject r=dispatch((JSONObject)parsed); out = r==null? null : r.toJSONString(); }

      if(out==null){ ex.sendResponseHeaders(202,-1); ex.close(); return; }  // only notifications -> Accepted
      send(ex,200,"application/json",out);
    }catch(Throwable t){ System.err.println("HTTP ERR "+t);
      try{ send(ex,200,"application/json","{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32000,\"message\":"+JSONObject.escape(t.toString())+"}}"); }catch(Exception e){} }
  }

  @SuppressWarnings("unchecked")
  static JSONObject dispatch(JSONObject req){
    Object id = req.get("id"); String method = (String)req.get("method");
    if(method==null) return null;
    try{
      if(method.equals("initialize")) return env(id, CrfMcpServer.initResult());
      if(method.startsWith("notifications/")) return null;
      if(method.equals("tools/list")) return env(id, CrfMcpServer.toolsList());
      if(method.equals("tools/call")) return env(id, CrfMcpServer.callTool((JSONObject)req.get("params")));
      if(id!=null) return errEnv(id, -32601, "method not found: "+method);
      return null;
    }catch(Throwable t){ return id!=null? errEnv(id,-32000,t.toString()) : null; }
  }

  @SuppressWarnings("unchecked")
  static JSONObject env(Object id, JSONObject result){ JSONObject o=new JSONObject(); o.put("jsonrpc","2.0"); o.put("id",id); o.put("result",result); return o; }
  @SuppressWarnings("unchecked")
  static JSONObject errEnv(Object id, int code, String msg){ JSONObject e=new JSONObject(); e.put("code",(long)code); e.put("message",msg); JSONObject o=new JSONObject(); o.put("jsonrpc","2.0"); o.put("id",id); o.put("error",e); return o; }

  static void send(HttpExchange ex, int code, String ctype, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", ctype);
    ex.sendResponseHeaders(code, b.length);
    try(OutputStream os=ex.getResponseBody()){ os.write(b); }
  }
}
