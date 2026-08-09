package com.dnl.appenv.pro.xposed;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class JscTracePatcher {
    static final String DEFAULT_KEY = "7705064d-5c6e-4e";
    static final String PATCH_MARKER = "DNL_APPENV_TRACE_V3";
    private static final String ORIGINAL_BUTTON_UUID = "562a2S4bxBBu4pUxNZWZ/Xu";
    private static final String ORIGINAL_VIEW_UUID = "39d2eREdBxCg4OMcI2setoK";

    static final class Result {
        boolean success;
        boolean alreadyPatched;
        boolean traceInjected;
        int debugPatchCount;
        byte[] output;
        String sourceSha256 = "";
        String outputSha256 = "";
        String originalPlain = "";
        String patchedPlain = "";
        String message = "";
    }

    private JscTracePatcher() { }

    static Result patch(byte[] original, String traceFilePath,
                        boolean traceEnabled, boolean debugEnabled) {
        Result result = new Result();
        result.output = original;
        result.sourceSha256 = sha256(original);
        try {
            byte[] compressed = Xxtea.decrypt(original, DEFAULT_KEY);
            if (!isGzip(compressed)) {
                result.message = "FORMAT_NOT_XXTEA_GZIP";
                return result;
            }

            String source = gunzip(compressed);
            result.originalPlain = source;
            String patched = source;

            if (debugEnabled) {
                boolean featurePresent = source.contains(ORIGINAL_BUTTON_UUID)
                        && source.contains(ORIGINAL_VIEW_UUID)
                        && source.contains("DebugView");
                if (featurePresent) {
                    int before = 0;
                    String exactNeedle = "this.node.active = !!e;";
                    int exactCount = countLiteral(patched, exactNeedle);
                    if (exactCount > 0) {
                        patched = patched.replace(exactNeedle, "this.node.active = !0;");
                        before += exactCount;
                    }

                    Pattern flexible = Pattern.compile(
                            "this\\.node\\.active\\s*=\\s*!![_$A-Za-z][_$A-Za-z0-9]*\\s*;");
                    Matcher matcher = flexible.matcher(patched);
                    StringBuffer sb = new StringBuffer();
                    int flexibleCount = 0;
                    while (matcher.find()) {
                        flexibleCount++;
                        matcher.appendReplacement(sb, Matcher.quoteReplacement("this.node.active=!0;"));
                    }
                    matcher.appendTail(sb);
                    if (flexibleCount > 0) patched = sb.toString();
                    before += flexibleCount;
                    result.debugPatchCount = before;
                }
            }

            if (traceEnabled && !patched.contains(PATCH_MARKER)) {
                patched = patched + "\n" + buildTraceInjector(traceFilePath) + "\n";
                result.traceInjected = true;
            } else if (traceEnabled) {
                result.alreadyPatched = true;
                result.traceInjected = true;
            }

            if (!traceEnabled && !debugEnabled) {
                result.success = true;
                result.patchedPlain = source;
                result.outputSha256 = result.sourceSha256;
                result.message = "PATCH_DISABLED";
                return result;
            }

            result.patchedPlain = patched;
            byte[] encoded = Xxtea.encrypt(gzip(patched), DEFAULT_KEY);
            String verify = gunzip(Xxtea.decrypt(encoded, DEFAULT_KEY));
            if (traceEnabled && !verify.contains(PATCH_MARKER)) {
                result.message = "TRACE_VERIFY_FAILED";
                return result;
            }
            if (debugEnabled && result.debugPatchCount > 0
                    && !verify.contains("this.node.active = !0;")
                    && !verify.contains("this.node.active=!0;")) {
                result.message = "DEBUG_VERIFY_FAILED";
                return result;
            }

            result.success = true;
            result.output = encoded;
            result.outputSha256 = sha256(encoded);
            result.message = "PATCH_READY";
            return result;
        } catch (Throwable error) {
            result.message = "PATCH_EXCEPTION:" + error.getClass().getName()
                    + ":" + String.valueOf(error.getMessage());
            return result;
        }
    }

    private static String buildTraceInjector(String traceFilePath) {
        String path = jsQuote(traceFilePath == null ? "" : traceFilePath);
        return "/* " + PATCH_MARKER + " */\n"
                + ";(function(){\n"
                + "try{\n"
                + "var G=(typeof window!=='undefined'?window:this);\n"
                + "if(G.__DNL_APPENV_TRACE_V3)return;G.__DNL_APPENV_TRACE_V3=1;\n"
                + "var P='" + path + "';\n"
                + "var F=(typeof jsb!=='undefined'&&jsb.fileUtils)?jsb.fileUtils:null;\n"
                + "var S=0;\n"
                + "function q(v){try{if(v===undefined)return '<undefined>';if(v===null)return 'null';"
                + "if(typeof v==='string')return v;if(typeof v==='object')return JSON.stringify(v);return String(v);}catch(e){try{return String(v);}catch(_){return '<unprintable>';}}}\n"
                + "function w(tag,obj){try{var line=(new Date()).toISOString()+' ['+tag+'] '+q(obj)+'\\n';"
                + "if(typeof console!=='undefined'&&console.log)console.log('DNLHTTP '+line);"
                + "if(!F||!P)return;var old='';if(F.isFileExist(P)){old=F.getStringFromFile(P)||'';}"
                + "if(old.length>8388608)old=old.slice(old.length-4194304);F.writeStringToFile(old+line,P);}catch(e){}}\n"
                + "if(typeof XMLHttpRequest==='undefined'){w('TRACE_ERROR','XMLHttpRequest unavailable');return;}\n"
                + "var X=XMLHttpRequest.prototype,O=X.open,H=X.setRequestHeader,D=X.send;\n"
                + "X.open=function(m,u,a,b,c){this.__dnl={id:++S,method:m,url:u,headers:{},start:Date.now()};return O.apply(this,arguments);};\n"
                + "X.setRequestHeader=function(k,v){try{if(this.__dnl)this.__dnl.headers[k]=v;}catch(e){}return H.apply(this,arguments);};\n"
                + "X.send=function(body){var self=this,d=this.__dnl||{id:++S,method:'?',url:'?',headers:{},start:Date.now()};this.__dnl=d;d.body=q(body);"
                + "w('HTTP-REQ #'+d.id,{method:d.method,url:d.url,headers:d.headers,body:d.body});"
                + "var done=function(){try{if(self.readyState!==4||d.done)return;d.done=1;var rb='';"
                + "try{if(self.responseType===''||self.responseType==='text')rb=self.responseText;else rb=q(self.response);}catch(e){rb='<response-unavailable:'+e+'>';};"
                + "var rh='';try{rh=self.getAllResponseHeaders();}catch(e){}"
                + "w('HTTP-RESP #'+d.id,{status:self.status,statusText:self.statusText,url:d.url,elapsed:Date.now()-d.start,headers:rh,response:rb});}catch(e){w('HTTP-RESP-ERROR #'+d.id,String(e));}};"
                + "try{this.addEventListener('readystatechange',done);}catch(e){var oldcb=this.onreadystatechange;this.onreadystatechange=function(){done();if(oldcb)return oldcb.apply(this,arguments);};}"
                + "return D.apply(this,arguments);};\n"
                + "w('TRACE_READY',{marker:'" + PATCH_MARKER + "',file:P});\n"
                + "}catch(e){try{console.log('DNLHTTP TRACE_BOOT_ERROR '+e);}catch(_){}}\n"
                + "})();";
    }

    private static String jsQuote(String value) {
        return value.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static int countLiteral(String source, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = source.indexOf(needle, from);
            if (at < 0) return count;
            count++;
            from = at + needle.length();
        }
    }

    private static boolean isGzip(byte[] value) {
        return value != null && value.length >= 3
                && (value[0] & 0xFF) == 0x1F
                && (value[1] & 0xFF) == 0x8B
                && (value[2] & 0xFF) == 0x08;
    }

    private static String gunzip(byte[] input) throws Exception {
        GZIPInputStream gzip = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            gzip = new GZIPInputStream(new ByteArrayInputStream(input));
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = gzip.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        } finally {
            if (gzip != null) try { gzip.close(); } catch (Throwable ignored) { }
            try { output.close(); } catch (Throwable ignored) { }
        }
    }

    private static byte[] gzip(String input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        GZIPOutputStream gzip = null;
        try {
            gzip = new GZIPOutputStream(output);
            gzip.write(input.getBytes("UTF-8"));
            gzip.finish();
            return output.toByteArray();
        } finally {
            if (gzip != null) try { gzip.close(); } catch (Throwable ignored) { }
            try { output.close(); } catch (Throwable ignored) { }
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder output = new StringBuilder(hash.length * 2);
            for (byte b : hash) output.append(String.format(Locale.US, "%02x", b & 0xFF));
            return output.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
