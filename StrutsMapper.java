import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

/**
 * StrutsMapper - builds a browsable map of a Struts 1 project.
 *
 * Java 8+, no external libraries. This source file is pure ASCII on purpose,
 * so it compiles correctly regardless of the Eclipse workspace encoding.
 *
 * Usage:
 *   java StrutsMapper <project-root> [output.html]
 *
 * The file viewer-template.html must be on the classpath (same folder as the
 * class) or in the current working directory.
 *
 * Optional system properties:
 *   -Dsm.encoding=MS950   fallback encoding for files that are not valid UTF-8
 *   -Dsm.skip=a,b,c       extra directory names to skip
 */
public class StrutsMapper {

    static final String TOOL_VERSION = "1.0";
    static final int MAX_DEPTH = 12;
    static final int MAX_VISIT = 3000;
    static final int MAX_OVERRIDES = 8;
    static final Set<String> SKIP_DIRS = new HashSet<String>(Arrays.asList(
            ".git", ".svn", ".hg", ".idea", ".settings", ".metadata", "node_modules", "target", "bin", "classes"));
    static final Charset FALLBACK = pickFallback();

    // ------------------------------------------------------------------ model

    static class Forward {
        String name, path;
        boolean redirect, contextRelative;
    }

    static class FormBean {
        String name, type, module;
        boolean dyna;
        List<String[]> props = new ArrayList<String[]>();
    }

    static class ActionDef {
        String id, module, path, fullPath, type, form, scope, input, parameter, fwdAttr, includeAttr, file;
        boolean scopeDefault, validate = true, registered = true;
        int line;
        List<Forward> forwards = new ArrayList<Forward>();
        List<String[]> exceptions = new ArrayList<String[]>();
    }

    static class TileDef {
        String name, path, ext;
        List<String[]> puts = new ArrayList<String[]>();
    }

    static class JLit {
        int start, end;
        String value;
    }

    static class JMethod {
        JClass owner;
        String name, ret, params;
        int line, bodyStart = -1, bodyEnd = -1;
        boolean analyzed;
        List<String[]> calls = new ArrayList<String[]>(); // {targetFqn, name, "this"|""}
        List<String[]> spHits = new ArrayList<String[]>(); // {name, kind}
        Set<String> tables = new LinkedHashSet<String>();
        List<String> fwdNames = new ArrayList<String>();
        List<String> fwdPaths = new ArrayList<String>();
        List<String> sessionSet = new ArrayList<String>();
        List<String> sessionGet = new ArrayList<String>();
        List<String> requestSet = new ArrayList<String>();
        List<String[]> keyMap = new ArrayList<String[]>();
    }

    static class JClass {
        String fqn, simple, pkg, file, superRaw, superFqn, src, masked;
        boolean isInterface, isAbstract;
        int line;
        List<String> ifaceRaw = new ArrayList<String>();
        List<String> ifaceFqn = new ArrayList<String>();
        List<String> imports;
        Map<String, String> fieldTypes = new HashMap<String, String>();
        Map<String, List<String[]>> constParts = new HashMap<String, List<String[]>>();
        Map<String, String> constCache = new HashMap<String, String>();
        List<JMethod> methods = new ArrayList<JMethod>();
        List<JLit> lits;
        Map<Integer, JLit> litMap;
        int[] braceMatch, lineStarts;
    }

    // ------------------------------------------------------------------ state

    final Path root;
    final List<Path> xmlFiles = new ArrayList<Path>();
    final List<Path> javaFiles = new ArrayList<Path>();
    final List<Path> jspFiles = new ArrayList<Path>();
    final List<Path> propFiles = new ArrayList<Path>();
    final List<String> jarNames = new ArrayList<String>();
    final List<Path> jarPaths = new ArrayList<Path>();
    final Map<String, String> jarOfClass = new HashMap<String, String>();
    final Map<String, String> jspSuffix = new HashMap<String, String>();
    final List<Path> webRoots = new ArrayList<Path>();

    final Map<String, String> configModule = new LinkedHashMap<String, String>();
    String actionPattern = "*.do";
    final List<Map<String, Object>> filters = new ArrayList<Map<String, Object>>();
    final List<String> listeners = new ArrayList<String>();
    final Set<String> plugins = new LinkedHashSet<String>();
    final Set<String> messageBundles = new LinkedHashSet<String>();
    final Set<String> delegatingModules = new HashSet<String>();

    final List<ActionDef> actions = new ArrayList<ActionDef>();
    final Map<String, ActionDef> actionByFull = new HashMap<String, ActionDef>();
    final Map<String, FormBean> forms = new HashMap<String, FormBean>();
    final Map<String, List<Forward>> globalForwards = new LinkedHashMap<String, List<Forward>>();
    final List<Map<String, Object>> configFiles = new ArrayList<Map<String, Object>>();
    final Map<String, TileDef> tiles = new LinkedHashMap<String, TileDef>();
    final Map<String, String> springBeans = new HashMap<String, String>();
    final Map<String, List<String[]>> validations = new HashMap<String, List<String[]>>();

    final Map<String, JClass> classes = new HashMap<String, JClass>();
    final Map<String, List<String>> simpleIndex = new HashMap<String, List<String>>();
    final Map<String, List<String>> subIndex = new HashMap<String, List<String>>();
    final Map<String, List<JMethod>> targetCache = new HashMap<String, List<JMethod>>();

    final Map<String, Map<String, Object>> jspInfo = new TreeMap<String, Map<String, Object>>();
    final Properties messages = new Properties();
    final List<Map<String, Object>> warnings = new ArrayList<Map<String, Object>>();

    StrutsMapper(Path root) {
        this.root = root;
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java StrutsMapper <project-root> [output.html]");
            return;
        }
        Path root = Paths.get(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.out.println("Not a directory: " + root);
            return;
        }
        Path out = Paths.get(args.length > 1 ? args[1] : "struts-map.html").toAbsolutePath().normalize();
        String extraSkip = System.getProperty("sm.skip");
        if (extraSkip != null) {
            for (String s : extraSkip.split(",")) if (!s.trim().isEmpty()) SKIP_DIRS.add(s.trim());
        }

        String template = loadTemplate();
        if (template == null) {
            System.out.println("Cannot find viewer-template.html (put it next to StrutsMapper.class or in the working directory).");
            return;
        }

        long t0 = System.currentTimeMillis();
        StrutsMapper sm = new StrutsMapper(root);
        sm.scanFiles();
        log("Files: xml=" + sm.xmlFiles.size() + " java=" + sm.javaFiles.size() + " jsp=" + sm.jspFiles.size());
        sm.parseXmls();
        log("Actions: " + sm.actions.size() + ", tiles: " + sm.tiles.size() + ", spring beans: " + sm.springBeans.size());
        sm.parseJava();
        log("Java classes: " + sm.classes.size());
        sm.loadMessages();
        sm.parseJsps();
        Map<String, Object> data = sm.build();
        String json = toJson(data);

        String html = template.replace("__STRUTS_MAP_DATA__", json);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.write(out, html.getBytes(StandardCharsets.UTF_8));
        String outName = out.getFileName().toString();
        Path jsonOut = out.resolveSibling(outName.replaceAll("\\.html?$", "") + ".json");
        Files.write(jsonOut, json.getBytes(StandardCharsets.UTF_8));
        log("Warnings: " + sm.warnings.size());
        log("Done in " + (System.currentTimeMillis() - t0) + " ms");
        log("HTML: " + out);
        log("JSON: " + jsonOut);
    }

    static void log(String s) {
        System.out.println("[StrutsMapper] " + s);
    }

    static Charset pickFallback() {
        String name = System.getProperty("sm.encoding", "MS950");
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    static String loadTemplate() throws IOException {
        InputStream in = StrutsMapper.class.getResourceAsStream("viewer-template.html");
        if (in == null) in = StrutsMapper.class.getResourceAsStream("/viewer-template.html");
        if (in != null) {
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                return decode(bo.toByteArray());
            } finally {
                in.close();
            }
        }
        Path p = Paths.get("viewer-template.html");
        if (Files.exists(p)) return read(p);
        return null;
    }

    // ------------------------------------------------------------------ files

    static String read(Path p) {
        try {
            return decode(Files.readAllBytes(p));
        } catch (IOException e) {
            return "";
        }
    }

    static String decode(byte[] b) {
        int off = 0;
        if (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) off = 3;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, b.length - off)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, off, b.length - off, FALLBACK);
        }
    }

    String rel(Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    void scanFiles() throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".xml")) xmlFiles.add(f);
                else if (n.endsWith(".java")) javaFiles.add(f);
                else if (n.endsWith(".jsp") || n.endsWith(".jspf")) jspFiles.add(f);
                else if (n.endsWith(".properties")) propFiles.add(f);
                else if (n.endsWith(".jar")) {
                    jarNames.add(f.getFileName().toString());
                    jarPaths.add(f);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(xmlFiles);
        Collections.sort(javaFiles);
        Collections.sort(jspFiles);
        for (Path x : xmlFiles) {
            if (x.getFileName().toString().equals("web.xml") && x.getParent() != null
                    && x.getParent().getFileName().toString().equals("WEB-INF")) {
                webRoots.add(x.getParent().getParent());
            }
        }
    }

    Path webRootOf(Path p) {
        Path best = null;
        for (Path w : webRoots) {
            if (p.startsWith(w) && (best == null || w.getNameCount() > best.getNameCount())) best = w;
        }
        return best;
    }

    String webPath(Path p) {
        Path w = webRootOf(p);
        String r = (w != null ? w.relativize(p) : root.relativize(p)).toString().replace('\\', '/');
        return "/" + r;
    }

    // ------------------------------------------------------------------ xml

    static Document parseXml(String text) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setValidating(false);
            f.setNamespaceAware(false);
            trySet(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            trySet(f, "http://xml.org/sax/features/external-general-entities", false);
            trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver(new EntityResolver() {
                public InputSource resolveEntity(String publicId, String systemId) {
                    return new InputSource(new StringReader(""));
                }
            });
            b.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException e) { }
                public void error(SAXParseException e) { }
                public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
            });
            return b.parse(new InputSource(new StringReader(text)));
        } catch (Exception e) {
            return null;
        }
    }

    static void trySet(DocumentBuilderFactory f, String feature, boolean v) {
        try {
            f.setFeature(feature, v);
        } catch (Exception ignored) {
        }
    }

    static String tag(Node n) {
        String t = n.getNodeName();
        int i = t.indexOf(':');
        return i >= 0 ? t.substring(i + 1) : t;
    }

    static List<Element> kids(Element e, String name) {
        List<Element> r = new ArrayList<Element>();
        if (e == null) return r;
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && (name == null || tag(n).equals(name))) r.add((Element) n);
        }
        return r;
    }

    static Element kid(Element e, String name) {
        List<Element> l = kids(e, name);
        return l.isEmpty() ? null : l.get(0);
    }

    static String kidText(Element e, String name) {
        Element k = kid(e, name);
        return k == null ? null : k.getTextContent().trim();
    }

    static List<Element> all(Element e, String name) {
        List<Element> r = new ArrayList<Element>();
        NodeList nl = e.getElementsByTagName("*");
        for (int i = 0; i < nl.getLength(); i++) if (tag(nl.item(i)).equals(name)) r.add((Element) nl.item(i));
        return r;
    }

    static String attr(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name).trim() : null;
    }

    static int lineOfText(String text, int idx) {
        int line = 1;
        for (int i = 0; i < idx && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    void parseXmls() {
        List<Object[]> docs = new ArrayList<Object[]>();
        for (Path p : xmlFiles) {
            String text = read(p);
            String kind = null;
            if (text.contains("<web-app")) kind = "web-app";
            else if (text.contains("<struts-config")) kind = "struts-config";
            else if (text.contains("<tiles-definitions")) kind = "tiles-definitions";
            else if (text.contains("<form-validation")) kind = "form-validation";
            else if (text.contains("<beans")) kind = "beans";
            if (kind == null) continue;
            Document d = parseXml(text);
            if (d == null) {
                warn("xml-parse", "file", rel(p));
                continue;
            }
            Element r = d.getDocumentElement();
            if (!tag(r).equals(kind)) continue;
            docs.add(new Object[] {p, text, r, kind});
        }
        for (Object[] o : docs) if ("web-app".equals(o[3])) parseWebXml((Path) o[0], (Element) o[2]);
        // Spring first: some frameworks register struts-config files per module from Spring beans
        for (Object[] o : docs) if ("beans".equals(o[3])) parseSpring((Element) o[2]);
        for (Object[] o : docs) {
            String k = (String) o[3];
            if ("struts-config".equals(k)) parseStrutsConfig((Path) o[0], (String) o[1], (Element) o[2]);
            else if ("tiles-definitions".equals(k)) parseTiles((Element) o[2]);
            else if ("form-validation".equals(k)) parseValidation((Element) o[2]);
        }
    }

    void parseWebXml(Path p, Element r) {
        Set<String> actionServlets = new HashSet<String>();
        for (Element s : kids(r, "servlet")) {
            String name = kidText(s, "servlet-name");
            String cls = kidText(s, "servlet-class");
            boolean isStruts = cls != null && cls.contains("ActionServlet");
            for (Element ip : kids(s, "init-param")) {
                String pn = kidText(ip, "param-name");
                String pv = kidText(ip, "param-value");
                if (pn == null || pv == null) continue;
                if (pn.equals("config") || pn.startsWith("config/")) {
                    isStruts = true;
                    String module = pn.equals("config") ? "" : "/" + pn.substring("config/".length());
                    for (String v : pv.split(",")) {
                        String key = v.trim().replace('\\', '/');
                        while (key.startsWith("/")) key = key.substring(1);
                        if (!key.isEmpty()) configModule.put(key, module);
                    }
                }
            }
            if (isStruts && name != null) actionServlets.add(name);
        }
        if (configModule.isEmpty() && !actionServlets.isEmpty()) configModule.put("WEB-INF/struts-config.xml", "");
        for (Element m : kids(r, "servlet-mapping")) {
            if (actionServlets.contains(kidText(m, "servlet-name"))) {
                String u = kidText(m, "url-pattern");
                if (u != null) actionPattern = u;
            }
        }
        Map<String, Map<String, Object>> fmap = new LinkedHashMap<String, Map<String, Object>>();
        for (Element f : kids(r, "filter")) {
            Map<String, Object> fm = new LinkedHashMap<String, Object>();
            fm.put("name", kidText(f, "filter-name"));
            fm.put("cls", kidText(f, "filter-class"));
            fm.put("patterns", new ArrayList<String>());
            fmap.put(kidText(f, "filter-name"), fm);
        }
        for (Element fm : kids(r, "filter-mapping")) {
            Map<String, Object> f = fmap.get(kidText(fm, "filter-name"));
            if (f == null) continue;
            @SuppressWarnings("unchecked")
            List<String> pats = (List<String>) f.get("patterns");
            for (Element u : kids(fm, "url-pattern")) pats.add(u.getTextContent().trim());
            for (Element u : kids(fm, "servlet-name")) pats.add("servlet:" + u.getTextContent().trim());
        }
        filters.addAll(fmap.values());
        for (Element l : kids(r, "listener")) {
            String c = kidText(l, "listener-class");
            if (c != null) listeners.add(c);
        }
    }

    String moduleOf(Path p, boolean[] registered) {
        String rp = rel(p);
        Path w = webRootOf(p);
        String wp = w != null ? w.relativize(p).toString().replace('\\', '/') : rp;
        for (Map.Entry<String, String> e : configModule.entrySet()) {
            if (wp.equals(e.getKey()) || rp.endsWith(e.getKey())) {
                registered[0] = true;
                return e.getValue();
            }
        }
        String fn = p.getFileName().toString();
        String found = null;
        int n = 0;
        for (Map.Entry<String, String> e : configModule.entrySet()) {
            if (e.getKey().equals(fn) || e.getKey().endsWith("/" + fn)) {
                found = e.getValue();
                n++;
            }
        }
        if (n == 1) {
            registered[0] = true;
            return found;
        }
        registered[0] = configModule.isEmpty();
        return "";
    }

    void parseStrutsConfig(Path p, String text, Element r) {
        boolean[] reg = new boolean[1];
        String module = moduleOf(p, reg);
        String file = rel(p);
        if (!reg[0]) warn("config-unregistered", "file", file);

        Element ctl = kid(r, "controller");
        if (ctl != null) {
            String pc = attr(ctl, "processorClass");
            if (pc != null && pc.contains("Delegating")) delegatingModules.add(module);
        }
        for (Element fb : kids(kid(r, "form-beans"), "form-bean")) {
            FormBean f = new FormBean();
            f.name = attr(fb, "name");
            f.type = attr(fb, "type");
            f.module = module;
            f.dyna = f.type != null && f.type.contains("Dyna");
            for (Element fp : kids(fb, "form-property")) f.props.add(new String[] {attr(fp, "name"), attr(fp, "type")});
            if (f.name != null) forms.put(module + "|" + f.name, f);
        }
        List<Forward> gl = globalForwards.get(module);
        if (gl == null) globalForwards.put(module, gl = new ArrayList<Forward>());
        for (Element fe : kids(kid(r, "global-forwards"), "forward")) gl.add(toForward(fe));
        for (Element mr : kids(r, "message-resources")) {
            String pm = attr(mr, "parameter");
            if (pm != null) messageBundles.add(pm);
        }
        for (Element pi : kids(r, "plug-in")) {
            String c = attr(pi, "className");
            if (c != null) plugins.add(c);
        }
        int count = 0;
        for (Element ae : kids(kid(r, "action-mappings"), "action")) {
            ActionDef a = new ActionDef();
            a.module = module;
            a.path = attr(ae, "path");
            if (a.path == null) continue;
            a.fullPath = module + a.path;
            a.id = a.fullPath;
            a.type = attr(ae, "type");
            a.form = attr(ae, "name");
            a.scope = attr(ae, "scope");
            if (a.scope == null && a.form != null) {
                a.scope = "session";
                a.scopeDefault = true;
            }
            String v = attr(ae, "validate");
            a.validate = v == null || !v.equalsIgnoreCase("false");
            a.input = attr(ae, "input");
            a.parameter = attr(ae, "parameter");
            a.fwdAttr = attr(ae, "forward");
            a.includeAttr = attr(ae, "include");
            a.file = file;
            a.registered = reg[0];
            Matcher lm = Pattern.compile("path\\s*=\\s*[\"']" + Pattern.quote(a.path) + "[\"']").matcher(text);
            a.line = lm.find() ? lineOfText(text, lm.start()) : 0;
            for (Element fe : kids(ae, "forward")) a.forwards.add(toForward(fe));
            for (Element ex : kids(ae, "exception")) a.exceptions.add(new String[] {attr(ex, "type"), attr(ex, "path"), attr(ex, "key")});
            ActionDef dup = actionByFull.get(a.fullPath);
            if (dup != null) {
                warn("dup-action", "path", a.fullPath, "file", file, "other", dup.file);
                a.id = a.fullPath + "#" + file;
            } else {
                actionByFull.put(a.fullPath, a);
            }
            actions.add(a);
            count++;
        }
        Map<String, Object> cf = new LinkedHashMap<String, Object>();
        cf.put("file", file);
        cf.put("module", module);
        cf.put("registered", reg[0]);
        cf.put("actions", count);
        configFiles.add(cf);
    }

    static Forward toForward(Element fe) {
        Forward f = new Forward();
        f.name = attr(fe, "name");
        f.path = attr(fe, "path");
        f.redirect = "true".equalsIgnoreCase(attr(fe, "redirect"));
        f.contextRelative = "true".equalsIgnoreCase(attr(fe, "contextRelative"));
        return f;
    }

    void parseTiles(Element r) {
        for (Element de : kids(r, "definition")) {
            TileDef t = new TileDef();
            t.name = attr(de, "name");
            t.path = attr(de, "path");
            if (t.path == null) t.path = attr(de, "page");
            if (t.path == null) t.path = attr(de, "template");
            t.ext = attr(de, "extends");
            for (Element pe : kids(de, "put")) {
                String val = attr(pe, "value");
                if (val == null) val = attr(pe, "content");
                if (val == null) val = pe.getTextContent().trim();
                t.puts.add(new String[] {attr(pe, "name"), val});
            }
            if (t.name != null) tiles.put(t.name, t);
        }
    }

    void parseSpring(Element r) {
        Map<String, String> parents = new HashMap<String, String>();
        for (Element b : all(r, "bean")) {
            registerConfigFromBean(b);
            String cls = attr(b, "class");
            String parent = attr(b, "parent");
            List<String> names = new ArrayList<String>();
            String id = attr(b, "id");
            if (id != null) names.add(id);
            String nm = attr(b, "name");
            if (nm != null) for (String s : nm.split("[,;\\s]+")) if (!s.isEmpty()) names.add(s);
            for (String n : names) {
                if (cls != null) springBeans.put(n, cls);
                else if (parent != null) parents.put(n, parent);
            }
        }
        for (Map.Entry<String, String> e : parents.entrySet()) {
            String c = springBeans.get(e.getValue());
            if (c != null) springBeans.put(e.getKey(), c);
        }
        for (Element a : all(r, "alias")) {
            String n = attr(a, "name"), al = attr(a, "alias");
            if (n != null && al != null && springBeans.containsKey(n)) springBeans.put(al, springBeans.get(n));
        }
    }

    /**
     * Frameworks such as eBao register struts-config files from Spring, e.g.
     *   <bean class="...StrutsConfigExport">
     *     <property name="moduleName" value="/act"/>
     *     <property name="configFile"><list><value>/WEB-INF/.../struts-config-x.xml</value></list></property>
     */
    void registerConfigFromBean(Element b) {
        String module = null;
        List<String> files = new ArrayList<String>();
        for (Element pr : kids(b, "property")) {
            String name = attr(pr, "name");
            if (name == null) continue;
            List<String> vals = new ArrayList<String>();
            String v = attr(pr, "value");
            if (v != null) vals.add(v);
            for (Element ve : all(pr, "value")) vals.add(ve.getTextContent().trim());
            if (name.equals("moduleName") || name.equals("module") || name.equals("prefix")) {
                if (!vals.isEmpty()) module = vals.get(0);
            } else if (name.toLowerCase(Locale.ROOT).startsWith("configfile") || name.equals("configLocations")) {
                for (String x : vals) for (String y : x.split(",")) if (!y.trim().isEmpty()) files.add(y.trim());
            }
        }
        if (module == null || files.isEmpty()) return;
        module = module.trim();
        if (module.equals("/")) module = "";
        if (!module.isEmpty() && !module.startsWith("/")) module = "/" + module;
        while (module.endsWith("/")) module = module.substring(0, module.length() - 1);
        for (String f : files) {
            String key = f.replace('\\', '/');
            while (key.startsWith("/")) key = key.substring(1);
            configModule.put(key, module);
        }
    }

    void parseValidation(Element r) {
        for (Element fs : kids(r, "formset")) {
            for (Element fe : kids(fs, "form")) {
                String name = attr(fe, "name");
                if (name == null) continue;
                List<String[]> l = validations.get(name);
                if (l == null) validations.put(name, l = new ArrayList<String[]>());
                for (Element fld : kids(fe, "field")) l.add(new String[] {attr(fld, "property"), attr(fld, "depends")});
            }
        }
    }

    // ------------------------------------------------------------------ java parsing

    static final Pattern P_PACKAGE = Pattern.compile("\\bpackage\\s+([\\w$.]+)\\s*;");
    static final Pattern P_IMPORT = Pattern.compile("\\bimport\\s+(static\\s+)?([\\w$.]+(?:\\.\\*)?)\\s*;");
    static final Pattern P_TYPE = Pattern.compile("\\b(class|interface|enum)\\s+([A-Za-z_$][\\w$]*)");
    static final Pattern P_INNER_TYPE = Pattern.compile("\\b(class|interface|enum)\\s+[A-Za-z_$]");
    static final Pattern P_EXTENDS = Pattern.compile("(?s)\\bextends\\s+(.+?)(?=\\bimplements\\b|$)");
    static final Pattern P_IMPLEMENTS = Pattern.compile("(?s)\\bimplements\\s+(.+)$");
    static final Pattern P_METHOD = Pattern.compile("^([\\s\\S]*?)\\b([A-Za-z_$][\\w$]*)\\s*\\(([^()]*)\\)\\s*(?:throws\\s+[\\w$.,\\s]+)?\\s*$");
    static final Pattern P_FIELD = Pattern.compile("([A-Za-z_$][\\w$.]*(?:\\s*\\[\\s*\\])*)\\s+([A-Za-z_$][\\w$]*)\\s*(?:\\[\\s*\\])*\\s*$");
    static final Pattern P_ANNOT = Pattern.compile("@[A-Za-z_$][\\w$.]*(?:\\s*\\([^()]*\\))?");
    static final Pattern P_LOCAL = Pattern.compile("(?<![\\w$.])([A-Z][\\w$]*(?:\\.[A-Z][\\w$]*)*(?:\\s*\\[\\s*\\])*)\\s+([a-z_$][\\w$]*)\\s*(?=[=;:,)])");
    static final Pattern P_CAST_CALL = Pattern.compile("\\(\\s*\\(\\s*([A-Z][\\w$]*(?:\\.[\\w$]+)*)\\s*\\)");
    static final Pattern P_FIND_FWD = Pattern.compile("\\bfindForward\\s*\\(\\s*\"");
    static final Pattern P_NEW_FWD = Pattern.compile("\\bnew\\s+ActionForward\\s*\\(\\s*\"");
    static final Pattern P_ATTR = Pattern.compile("([\\w$.()]+?)\\s*\\.\\s*(setAttribute|getAttribute|removeAttribute)\\s*\\(\\s*\"");
    static final Pattern P_PUT = Pattern.compile("\\.\\s*put\\s*\\(\\s*\"");

    static final Set<String> MODIFIERS = new HashSet<String>(Arrays.asList(
            "public", "protected", "private", "static", "final", "abstract", "synchronized", "native",
            "transient", "volatile", "strictfp", "default"));
    static final Set<String> KEYWORDS = new HashSet<String>(Arrays.asList(
            "if", "for", "while", "switch", "catch", "return", "throw", "else", "do", "try", "finally",
            "instanceof", "case", "synchronized", "assert", "break", "continue", "default", "class",
            "true", "false", "null", "int", "long", "short", "byte", "char", "boolean", "double", "float",
            "void", "final", "static", "goto", "const", "import", "package"));

    void parseJava() {
        for (Path p : javaFiles) {
            try {
                parseJavaFile(p);
            } catch (RuntimeException e) {
                warn("java-parse", "file", rel(p), "error", String.valueOf(e));
            }
        }
        for (JClass c : classes.values()) {
            c.superFqn = c.superRaw == null ? null : resolveType(c, c.superRaw);
            for (String i : c.ifaceRaw) {
                String f = resolveType(c, i);
                if (f != null) c.ifaceFqn.add(f);
            }
            if (c.superFqn != null) addSub(c.superFqn, c.fqn);
            for (String f : c.ifaceFqn) addSub(f, c.fqn);
        }
    }

    void addSub(String sup, String sub) {
        List<String> l = subIndex.get(sup);
        if (l == null) subIndex.put(sup, l = new ArrayList<String>());
        l.add(sub);
    }

    void parseJavaFile(Path p) {
        String src = read(p);
        List<JLit> lits = new ArrayList<JLit>();
        String m = mask(src, lits);
        String pkg = "";
        Matcher pm = P_PACKAGE.matcher(m);
        if (pm.find()) pkg = pm.group(1);
        List<String> imports = new ArrayList<String>();
        Matcher im = P_IMPORT.matcher(m);
        while (im.find()) if (im.group(1) == null) imports.add(im.group(2));
        int[] bm = braces(m);
        int[] ls = lineStarts(src);
        Map<Integer, JLit> litMap = new HashMap<Integer, JLit>();
        for (JLit l : lits) litMap.put(l.start, l);
        int n = m.length();
        int[] depth = new int[n + 1];
        int d = 0;
        for (int i = 0; i < n; i++) {
            depth[i] = d;
            char c = m.charAt(i);
            if (c == '{') d++;
            else if (c == '}') d--;
        }
        depth[n] = d;

        Matcher tm = P_TYPE.matcher(m);
        int pos = 0;
        while (pos < n && tm.find(pos)) {
            int st = tm.start();
            if (depth[st] != 0 || prevNonSpace(m, st) == '.') {
                pos = tm.end();
                continue;
            }
            int open = m.indexOf('{', tm.end());
            if (open < 0) break;
            int close = bm[open] >= 0 ? bm[open] : n - 1;
            JClass c = new JClass();
            c.pkg = pkg;
            c.simple = tm.group(2);
            c.fqn = pkg.isEmpty() ? c.simple : pkg + "." + c.simple;
            c.file = rel(p);
            c.src = src;
            c.masked = m;
            c.lits = lits;
            c.litMap = litMap;
            c.braceMatch = bm;
            c.lineStarts = ls;
            c.imports = imports;
            c.line = lineOf(ls, tm.start(2));
            c.isInterface = tm.group(1).equals("interface");
            String header = stripGenerics(m.substring(tm.end(), open));
            int ps = Math.max(Math.max(m.lastIndexOf(';', st), m.lastIndexOf('}', st)), 0);
            String pre = m.substring(ps, st);
            c.isAbstract = c.isInterface || pre.matches("(?s).*\\babstract\\b.*");
            Matcher em = P_EXTENDS.matcher(header);
            List<String> ext = new ArrayList<String>();
            if (em.find()) for (String s : em.group(1).split(",")) if (!s.trim().isEmpty()) ext.add(s.trim());
            Matcher ipm = P_IMPLEMENTS.matcher(header);
            if (ipm.find()) for (String s : ipm.group(1).split(",")) if (!s.trim().isEmpty()) c.ifaceRaw.add(s.trim());
            if (c.isInterface) c.ifaceRaw.addAll(ext);
            else if (!ext.isEmpty()) c.superRaw = ext.get(0);
            parseMembers(c, open, close);
            if (!classes.containsKey(c.fqn)) {
                classes.put(c.fqn, c);
                List<String> l = simpleIndex.get(c.simple);
                if (l == null) simpleIndex.put(c.simple, l = new ArrayList<String>());
                l.add(c.fqn);
            }
            pos = close + 1;
        }
    }

    void parseMembers(JClass c, int open, int close) {
        String m = c.masked;
        int seg = open + 1;
        int i = open + 1;
        while (i < close) {
            char ch = m.charAt(i);
            if (ch == ';') {
                handleSegment(c, seg, i, false, -1);
                seg = i + 1;
                i++;
            } else if (ch == '{') {
                int end = c.braceMatch[i] >= 0 ? c.braceMatch[i] : close;
                handleSegment(c, seg, i, true, end);
                i = end + 1;
                seg = i;
            } else if (ch == '}') {
                i++;
                seg = i;
            } else {
                i++;
            }
        }
    }

    void handleSegment(JClass c, int s, int e, boolean hasBody, int bodyEnd) {
        String seg = blank(c.masked.substring(s, e), P_ANNOT);
        if (seg.trim().isEmpty()) return;
        if (P_INNER_TYPE.matcher(seg).find()) return;
        int eq = seg.indexOf('=');
        int paren = seg.indexOf('(');
        if (eq >= 0 && (paren < 0 || eq < paren)) {
            int initEnd = hasBody ? bodyEnd : e;
            if (hasBody) {
                int semi = c.masked.indexOf(';', bodyEnd);
                if (semi > 0) initEnd = semi;
            }
            parseField(c, seg.substring(0, eq), s + eq + 1, initEnd);
            return;
        }
        Matcher mm = P_METHOD.matcher(seg);
        if (mm.find()) {
            String name = mm.group(2);
            if (KEYWORDS.contains(name)) return;
            String prefix = stripGenerics(mm.group(1)).trim();
            List<String> tokens = new ArrayList<String>();
            for (String t : prefix.split("\\s+")) if (!t.isEmpty() && !MODIFIERS.contains(t)) tokens.add(t);
            String ret;
            if (tokens.isEmpty()) {
                if (!name.equals(c.simple)) return;
                ret = c.simple;
            } else {
                ret = tokens.get(tokens.size() - 1);
            }
            JMethod jm = new JMethod();
            jm.owner = c;
            jm.name = name;
            jm.ret = ret;
            jm.params = mm.group(3);
            jm.line = lineOf(c.lineStarts, s + mm.start(2));
            if (hasBody) {
                jm.bodyStart = e;
                jm.bodyEnd = bodyEnd;
            }
            c.methods.add(jm);
            return;
        }
        if (!hasBody) parseField(c, seg, -1, -1);
    }

    void parseField(JClass c, String decl, int initStart, int initEnd) {
        Matcher fm = P_FIELD.matcher(stripGenerics(decl).trim());
        if (!fm.find()) return;
        String type = fm.group(1).replaceAll("\\s+", "");
        String name = fm.group(2);
        if (MODIFIERS.contains(name) || KEYWORDS.contains(name)) return;
        c.fieldTypes.put(name, type);
        if (initStart < 0 || !type.equals("String")) return;
        List<String[]> parts = new ArrayList<String[]>();
        String m = c.masked;
        int k = initStart;
        while (k < initEnd && k < m.length()) {
            char ch = m.charAt(k);
            if (ch == '"') {
                JLit l = c.litMap.get(k);
                if (l != null) {
                    parts.add(new String[] {"L", l.value});
                    k = l.end + 1;
                    continue;
                }
            }
            if (Character.isJavaIdentifierStart(ch)) {
                int j = k;
                while (j < initEnd && (Character.isJavaIdentifierPart(m.charAt(j)) || m.charAt(j) == '.')) j++;
                parts.add(new String[] {"I", m.substring(k, j)});
                k = j;
                continue;
            }
            k++;
        }
        if (!parts.isEmpty()) c.constParts.put(name, parts);
    }

    // mask: blank out comments and string/char literal contents (quotes kept)
    static String mask(String s, List<JLit> lits) {
        char[] c = s.toCharArray();
        int n = c.length;
        int i = 0;
        while (i < n) {
            char ch = c[i];
            if (ch == '/' && i + 1 < n && c[i + 1] == '/') {
                while (i < n && c[i] != '\n') c[i++] = ' ';
            } else if (ch == '/' && i + 1 < n && c[i + 1] == '*') {
                c[i] = ' ';
                c[i + 1] = ' ';
                i += 2;
                while (i < n && !(c[i] == '*' && i + 1 < n && c[i + 1] == '/')) {
                    if (c[i] != '\n') c[i] = ' ';
                    i++;
                }
                if (i + 1 < n) {
                    c[i] = ' ';
                    c[i + 1] = ' ';
                    i += 2;
                } else {
                    i = n;
                }
            } else if (ch == '"') {
                int st = i;
                StringBuilder v = new StringBuilder();
                i++;
                while (i < n && c[i] != '"' && c[i] != '\n') {
                    if (c[i] == '\\' && i + 1 < n) {
                        v.append(unescape(c[i + 1]));
                        c[i] = ' ';
                        c[i + 1] = ' ';
                        i += 2;
                        continue;
                    }
                    v.append(c[i]);
                    c[i] = ' ';
                    i++;
                }
                JLit l = new JLit();
                l.start = st;
                l.end = Math.min(i, n - 1);
                l.value = v.toString();
                lits.add(l);
                i++;
            } else if (ch == '\'') {
                i++;
                while (i < n && c[i] != '\'' && c[i] != '\n') {
                    if (c[i] == '\\' && i + 1 < n) {
                        c[i] = ' ';
                        i++;
                    }
                    c[i] = ' ';
                    i++;
                }
                i++;
            } else {
                i++;
            }
        }
        return new String(c);
    }

    static char unescape(char c) {
        switch (c) {
            case 'n': return '\n';
            case 't': return '\t';
            case 'r': return '\r';
            default: return c;
        }
    }

    static int[] braces(String m) {
        int[] r = new int[m.length()];
        Arrays.fill(r, -1);
        Deque<Integer> st = new ArrayDeque<Integer>();
        for (int i = 0; i < m.length(); i++) {
            char c = m.charAt(i);
            if (c == '{') st.push(i);
            else if (c == '}' && !st.isEmpty()) {
                int o = st.pop();
                r[o] = i;
                r[i] = o;
            }
        }
        return r;
    }

    static int[] lineStarts(String s) {
        List<Integer> l = new ArrayList<Integer>();
        l.add(0);
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') l.add(i + 1);
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    static int lineOf(int[] ls, int idx) {
        int lo = 0, hi = ls.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (ls[mid] <= idx) lo = mid;
            else hi = mid - 1;
        }
        return lo + 1;
    }

    static String stripGenerics(String s) {
        String prev;
        do {
            prev = s;
            s = s.replaceAll("<[^<>]*>", "");
        } while (!s.equals(prev));
        return s;
    }

    static String blank(String s, Pattern p) {
        Matcher m = p.matcher(s);
        if (!m.find()) return s;
        StringBuilder sb = new StringBuilder(s);
        m.reset();
        while (m.find()) for (int i = m.start(); i < m.end(); i++) if (sb.charAt(i) != '\n') sb.setCharAt(i, ' ');
        return sb.toString();
    }

    static char prevNonSpace(String s, int i) {
        int j = i - 1;
        while (j >= 0 && Character.isWhitespace(s.charAt(j))) j--;
        return j >= 0 ? s.charAt(j) : 0;
    }

    static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    static int identEnd(String s, int i) {
        while (i < s.length() && Character.isJavaIdentifierPart(s.charAt(i))) i++;
        return i;
    }

    static int closeParen(String s, int open) {
        int d = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') d++;
            else if (c == ')') {
                d--;
                if (d == 0) return i;
            }
        }
        return s.length() - 1;
    }

    static int closeAngle(String s, int open) {
        int d = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') d++;
            else if (c == '>') {
                d--;
                if (d == 0) return i;
            } else if (c == ';' || c == '{') return i - 1;
        }
        return s.length() - 1;
    }

    // ------------------------------------------------------------------ type resolution

    String resolveType(JClass ctx, String raw) {
        if (raw == null) return null;
        String t = stripGenerics(raw).replace("[]", "").replace("...", "").replaceAll("\\s+", "");
        if (t.isEmpty() || !Character.isJavaIdentifierStart(t.charAt(0))) return null;
        if (classes.containsKey(t)) return t;
        if (t.indexOf('.') >= 0) return null;
        if (ctx != null) {
            if (ctx.simple.equals(t)) return ctx.fqn;
            for (String imp : ctx.imports) if (imp.endsWith("." + t)) return classes.containsKey(imp) ? imp : null;
            String same = ctx.pkg.isEmpty() ? t : ctx.pkg + "." + t;
            if (classes.containsKey(same)) return same;
            for (String imp : ctx.imports) {
                if (imp.endsWith(".*")) {
                    String cand = imp.substring(0, imp.length() - 1) + t;
                    if (classes.containsKey(cand)) return cand;
                }
            }
        }
        List<String> l = simpleIndex.get(t);
        if (l != null && l.size() == 1) return l.get(0);
        return null;
    }

    List<JClass> lineage(String fqn) {
        List<JClass> out = new ArrayList<JClass>();
        Deque<String> q = new ArrayDeque<String>();
        Set<String> seen = new HashSet<String>();
        if (fqn != null) q.add(fqn);
        while (!q.isEmpty()) {
            String f = q.poll();
            JClass c = classes.get(f);
            if (c == null || !seen.add(f)) continue;
            out.add(c);
            if (c.superFqn != null) q.add(c.superFqn);
            q.addAll(c.ifaceFqn);
        }
        return out;
    }

    String returnTypeOf(String fqn, String name) {
        for (JClass c : lineage(fqn)) for (JMethod m : c.methods) if (m.name.equals(name)) return resolveType(c, m.ret);
        return null;
    }

    String fieldTypeOf(String fqn, String name) {
        for (JClass c : lineage(fqn)) if (c.fieldTypes.containsKey(name)) return resolveType(c, c.fieldTypes.get(name));
        return null;
    }

    boolean hasField(String fqn, String name) {
        for (JClass c : lineage(fqn)) if (c.fieldTypes.containsKey(name)) return true;
        return false;
    }

    String constantOf(String fqn, String name, int depth) {
        if (depth > 6) return null;
        for (JClass c : lineage(fqn)) {
            if (!c.constParts.containsKey(name)) continue;
            if (c.constCache.containsKey(name)) return c.constCache.get(name);
            StringBuilder sb = new StringBuilder();
            for (String[] part : c.constParts.get(name)) {
                if (part[0].equals("L")) {
                    sb.append(part[1]);
                } else {
                    String id = part[1];
                    String v;
                    int dot = id.lastIndexOf('.');
                    if (dot > 0) v = constantOf(resolveType(c, id.substring(0, dot)), id.substring(dot + 1), depth + 1);
                    else v = constantOf(c.fqn, id, depth + 1);
                    if (v != null) sb.append(v);
                }
            }
            String v = sb.toString();
            c.constCache.put(name, v);
            return v;
        }
        return null;
    }

    Set<String> allSubtypes(String fqn) {
        Set<String> r = new LinkedHashSet<String>();
        Deque<String> q = new ArrayDeque<String>();
        q.add(fqn);
        while (!q.isEmpty()) {
            List<String> l = subIndex.get(q.poll());
            if (l == null) continue;
            for (String s : l) if (r.add(s)) q.add(s);
        }
        return r;
    }

    List<JMethod> nearestDeclared(String fqn, String name) {
        List<JMethod> r = new ArrayList<JMethod>();
        String f = fqn;
        Set<String> seen = new HashSet<String>();
        while (f != null && seen.add(f)) {
            JClass c = classes.get(f);
            if (c == null) break;
            for (JMethod m : c.methods) if (m.name.equals(name) && m.bodyStart >= 0) r.add(m);
            if (!r.isEmpty()) break;
            f = c.superFqn;
        }
        return r;
    }

    List<JMethod> targets(String fqn, String name) {
        String key = fqn + "#" + name;
        List<JMethod> cached = targetCache.get(key);
        if (cached != null) return cached;
        LinkedHashSet<JMethod> r = new LinkedHashSet<JMethod>(nearestDeclared(fqn, name));
        List<JMethod> overrides = new ArrayList<JMethod>();
        for (String sub : allSubtypes(fqn)) {
            JClass c = classes.get(sub);
            if (c == null) continue;
            for (JMethod m : c.methods) if (m.name.equals(name) && m.bodyStart >= 0) overrides.add(m);
        }
        if (overrides.size() <= MAX_OVERRIDES) r.addAll(overrides);
        List<JMethod> out = new ArrayList<JMethod>(r);
        targetCache.put(key, out);
        return out;
    }

    // ------------------------------------------------------------------ method analysis

    void analyze(JMethod jm) {
        if (jm.analyzed) return;
        jm.analyzed = true;
        if (jm.bodyStart < 0) return;
        JClass c = jm.owner;
        String m = c.masked;
        int bs = jm.bodyStart + 1, be = Math.min(jm.bodyEnd, m.length());
        if (be <= bs) return;
        String body = m.substring(bs, be);

        Map<String, String> locals = new HashMap<String, String>();
        for (String prm : stripGenerics(jm.params).split(",")) {
            String[] tk = blank(prm, P_ANNOT).trim().split("\\s+");
            if (tk.length >= 2) locals.put(tk[tk.length - 1].replace("[]", ""), tk[tk.length - 2].replace("...", ""));
        }
        Matcher lm = P_LOCAL.matcher(stripGenerics(body));
        while (lm.find()) locals.put(lm.group(2), lm.group(1));

        List<String> strings = new ArrayList<String>();
        for (JLit l : c.lits) if (l.start > bs && l.start < be) strings.add(l.value);

        scanChains(c, jm, body, locals, strings);

        Matcher cm = P_CAST_CALL.matcher(body);
        while (cm.find()) {
            int outer = cm.start();
            int close = closeParen(body, outer);
            int q = skipWs(body, close + 1);
            if (q < body.length() && body.charAt(q) == '.') {
                int r = skipWs(body, q + 1);
                int k = identEnd(body, r);
                if (k > r && skipWs(body, k) < body.length() && body.charAt(skipWs(body, k)) == '(') {
                    String t = resolveType(c, cm.group(1));
                    if (t != null) addCall(jm, t, body.substring(r, k), false);
                }
            }
        }

        for (String s : strings) {
            detectSp(s, jm.spHits);
            detectTables(s, jm.tables);
        }

        Matcher fm = P_FIND_FWD.matcher(body);
        while (fm.find()) {
            JLit l = c.litMap.get(bs + fm.end() - 1);
            if (l != null) addU(jm.fwdNames, l.value);
        }
        fm = P_NEW_FWD.matcher(body);
        while (fm.find()) {
            JLit l = c.litMap.get(bs + fm.end() - 1);
            if (l != null) addU(jm.fwdPaths, l.value);
        }
        Matcher am = P_ATTR.matcher(body);
        while (am.find()) {
            JLit l = c.litMap.get(bs + am.end() - 1);
            if (l == null) continue;
            String recv = am.group(1).toLowerCase(Locale.ROOT);
            String op = am.group(2);
            if (recv.contains("session")) {
                if (op.equals("getAttribute")) addU(jm.sessionGet, l.value);
                else addU(jm.sessionSet, l.value);
            } else if ((recv.contains("request") || recv.equals("req")) && op.equals("setAttribute")) {
                addU(jm.requestSet, l.value);
            }
        }
        if (jm.name.equals("getKeyMethodMap")) {
            Matcher pm = P_PUT.matcher(body);
            while (pm.find()) {
                JLit k = c.litMap.get(bs + pm.end() - 1);
                if (k == null) continue;
                int q = skipWs(m, k.end + 1);
                if (q < m.length() && m.charAt(q) == ',') {
                    q = skipWs(m, q + 1);
                    JLit v = c.litMap.get(q);
                    if (v != null) jm.keyMap.add(new String[] {k.value, v.value});
                }
            }
        }
    }

    static void addU(List<String> l, String v) {
        if (!l.contains(v)) l.add(v);
    }

    void addCall(JMethod jm, String fqn, String name, boolean viaThis) {
        for (String[] c : jm.calls) if (c[0].equals(fqn) && c[1].equals(name)) return;
        jm.calls.add(new String[] {fqn, name, viaThis ? "this" : ""});
    }

    void scanChains(JClass c, JMethod jm, String b, Map<String, String> locals, List<String> strings) {
        int n = b.length();
        int i = 0;
        while (i < n) {
            char ch = b.charAt(i);
            if (!Character.isJavaIdentifierStart(ch) || (i > 0 && Character.isJavaIdentifierPart(b.charAt(i - 1)))) {
                i++;
                continue;
            }
            int k = identEnd(b, i);
            if (prevNonSpace(b, i) != '.') processChain(c, jm, b, i, locals, strings);
            i = k;
        }
    }

    void processChain(JClass c, JMethod jm, String b, int start, Map<String, String> locals, List<String> strings) {
        int n = b.length();
        int j = start;
        String cur = null;
        boolean first = true;
        while (j < n) {
            int k = identEnd(b, j);
            String id = b.substring(j, k);
            int after;
            if (first && id.equals("new")) {
                int t0 = skipWs(b, k), t1 = t0;
                while (t1 < n && (Character.isJavaIdentifierPart(b.charAt(t1)) || b.charAt(t1) == '.')) t1++;
                if (t1 == t0) return;
                String tn = b.substring(t0, t1);
                int q = skipWs(b, t1);
                if (q < n && b.charAt(q) == '<') q = skipWs(b, closeAngle(b, q) + 1);
                if (q >= n || b.charAt(q) != '(') return;
                after = closeParen(b, q) + 1;
                cur = resolveType(c, tn);
            } else {
                if (KEYWORDS.contains(id) || id.equals("new")) return;
                int p = skipWs(b, k);
                boolean call = p < n && b.charAt(p) == '(';
                after = call ? closeParen(b, p) + 1 : k;
                if (first) {
                    if (call) {
                        if (id.equals("this") || id.equals("super")) {
                            cur = null;
                        } else {
                            addCall(jm, c.fqn, id, true);
                            cur = returnTypeOf(c.fqn, id);
                        }
                    } else if (id.equals("this")) {
                        cur = c.fqn;
                    } else if (id.equals("super")) {
                        cur = c.superFqn;
                    } else if (locals.containsKey(id)) {
                        cur = resolveType(c, locals.get(id));
                    } else if (hasField(c.fqn, id)) {
                        String cv = constantOf(c.fqn, id, 0);
                        if (cv != null) strings.add(cv);
                        cur = fieldTypeOf(c.fqn, id);
                    } else if (Character.isUpperCase(id.charAt(0))) {
                        cur = resolveType(c, id);
                    } else {
                        cur = null;
                    }
                } else if (call) {
                    if (cur != null) {
                        addCall(jm, cur, id, false);
                        cur = returnTypeOf(cur, id);
                    }
                } else if (cur != null) {
                    String cv = constantOf(cur, id, 0);
                    if (cv != null) {
                        strings.add(cv);
                        cur = null;
                    } else {
                        cur = fieldTypeOf(cur, id);
                    }
                }
            }
            first = false;
            int q = skipWs(b, after);
            if (q < n && b.charAt(q) == '.') {
                int r = skipWs(b, q + 1);
                if (r < n && Character.isJavaIdentifierStart(b.charAt(r))) {
                    j = r;
                    continue;
                }
            }
            return;
        }
    }

    // ------------------------------------------------------------------ SQL / SP detection

    static final String ID = "[A-Za-z][\\w$#]*";
    static final Pattern P_SP_CALL = Pattern.compile("(?i)(?:\\{\\s*(?:\\?\\s*=\\s*)?|^\\s*)call\\s+(" + ID + "(?:\\." + ID + "){0,2})(\\.?)");
    static final Pattern P_SP_BEGIN = Pattern.compile("(?i)\\bbegin\\s+(?:\\?\\s*:=\\s*)?(" + ID + "(?:\\." + ID + "){0,2})\\s*(?:\\(|;)");
    static final Pattern P_SP_FUNC = Pattern.compile("(?<![\\w$#.])(" + ID + "\\." + ID + "(?:\\." + ID + ")?)\\s*\\(");
    static final Pattern P_SP_OPEN = Pattern.compile("(?i)(?:\\{\\s*(?:\\?\\s*=\\s*)?|^\\s*)call\\s*$");
    static final Pattern P_SQL_START = Pattern.compile("(?is)^\\s*(select|insert|delete|merge|with|from|where|join|left|inner|update)\\b.*");
    static final Pattern P_SQL_SELECT = Pattern.compile("(?is).*\\bselect\\b.+\\bfrom\\b.*");
    static final Pattern P_TABLE = Pattern.compile("(?i)\\b(from|join|into)\\s+(" + ID + "(?:\\." + ID + ")?)\\b");
    static final Pattern P_UPDATE = Pattern.compile("(?i)\\bupdate\\s+(" + ID + "(?:\\." + ID + ")?)\\s+(?:" + ID + "\\s+)?set\\b");
    static final Set<String> NOT_TABLES = new HashSet<String>(Arrays.asList(
            "DUAL", "SELECT", "WHERE", "SET", "VALUES", "TABLE", "THE", "A", "AN", "TO", "BY", "AND", "OR", "NOT", "NULL"));

    static boolean looksSql(String s) {
        return P_SQL_START.matcher(s).matches() || P_SQL_SELECT.matcher(s).matches();
    }

    static void addSp(List<String[]> out, String name, String kind) {
        for (String[] h : out) if (h[0].equals(name)) return;
        out.add(new String[] {name, kind});
    }

    static void detectSp(String s, List<String[]> out) {
        if (s == null || s.length() < 5) return;
        boolean found = false;
        Matcher m = P_SP_CALL.matcher(s);
        while (m.find()) {
            String nm = m.group(1).toUpperCase(Locale.ROOT);
            if (".".equals(m.group(2))) addSp(out, nm + ".*", "dynamic");
            else addSp(out, nm, "call");
            found = true;
        }
        m = P_SP_BEGIN.matcher(s);
        while (m.find()) {
            String nm = m.group(1).toUpperCase(Locale.ROOT);
            if (nm.equals("NULL")) continue;
            addSp(out, nm, "call");
            found = true;
        }
        if (looksSql(s)) {
            m = P_SP_FUNC.matcher(s);
            while (m.find()) {
                addSp(out, m.group(1).toUpperCase(Locale.ROOT), "func");
                found = true;
            }
        }
        if (!found && P_SP_OPEN.matcher(s).find()) addSp(out, "(dynamic)", "dynamic");
    }

    static void detectTables(String s, Set<String> out) {
        if (s == null || s.length() < 6) return;
        Matcher m = P_UPDATE.matcher(s);
        while (m.find()) addTable(out, m.group(1));
        if (!looksSql(s)) return;
        m = P_TABLE.matcher(s);
        while (m.find()) {
            boolean isInto = m.group(1).equalsIgnoreCase("into");
            int q = skipWs(s, m.end());
            if (!isInto && q < s.length() && s.charAt(q) == '(') continue; // from TABLE(...) / function
            addTable(out, m.group(2));
        }
    }

    static void addTable(Set<String> out, String t) {
        String u = t.toUpperCase(Locale.ROOT);
        if (!NOT_TABLES.contains(u)) out.add(u);
    }

    // ------------------------------------------------------------------ reachability

    static class Reach {
        Map<String, Object[]> sps = new LinkedHashMap<String, Object[]>(); // name -> {kind, List<JMethod> path}
        Set<String> tables = new LinkedHashSet<String>();
        boolean truncated;
    }

    Reach reach(JMethod start, String rootFqn) {
        Reach r = new Reach();
        Map<JMethod, JMethod> parent = new IdentityHashMap<JMethod, JMethod>();
        Map<JMethod, Integer> depth = new IdentityHashMap<JMethod, Integer>();
        Deque<JMethod> q = new ArrayDeque<JMethod>();
        parent.put(start, null);
        depth.put(start, 0);
        q.add(start);
        int visited = 0;
        Set<String> rootLineage = new HashSet<String>();
        for (JClass c : lineage(rootFqn)) rootLineage.add(c.fqn);
        while (!q.isEmpty()) {
            JMethod m = q.poll();
            if (++visited > MAX_VISIT) {
                r.truncated = true;
                break;
            }
            analyze(m);
            for (String[] h : m.spHits) {
                if (!r.sps.containsKey(h[0])) r.sps.put(h[0], new Object[] {h[1], pathTo(parent, m)});
            }
            r.tables.addAll(m.tables);
            int d = depth.get(m);
            if (d >= MAX_DEPTH) continue;
            for (String[] call : m.calls) {
                List<JMethod> ts;
                if (call[2].equals("this") && rootLineage.contains(m.owner.fqn)) ts = nearestDeclared(rootFqn, call[1]);
                else ts = targets(call[0], call[1]);
                if (ts.isEmpty() && call[2].equals("this")) ts = targets(call[0], call[1]);
                for (JMethod t : ts) {
                    if (parent.containsKey(t)) continue;
                    parent.put(t, m);
                    depth.put(t, d + 1);
                    q.add(t);
                }
            }
        }
        return r;
    }

    static List<JMethod> pathTo(Map<JMethod, JMethod> parent, JMethod m) {
        LinkedList<JMethod> l = new LinkedList<JMethod>();
        for (JMethod x = m; x != null; x = parent.get(x)) l.addFirst(x);
        return l;
    }

    static List<Map<String, Object>> viaJson(List<JMethod> path) {
        List<Map<String, Object>> r = new ArrayList<Map<String, Object>>();
        for (JMethod m : path) {
            Map<String, Object> o = new LinkedHashMap<String, Object>();
            o.put("cls", m.owner.simple);
            o.put("method", m.name);
            o.put("file", m.owner.file);
            o.put("line", m.line);
            r.add(o);
        }
        return r;
    }

    // ------------------------------------------------------------------ messages / jsp

    void loadMessages() {
        Set<String> bases = new HashSet<String>();
        for (String b : messageBundles) {
            String last = b.substring(b.lastIndexOf('.') + 1);
            last = last.substring(last.lastIndexOf('/') + 1);
            bases.add(last);
        }
        if (bases.isEmpty()) bases.add("ApplicationResources");
        Map<Integer, List<Path>> byRank = new TreeMap<Integer, List<Path>>();
        for (Path p : propFiles) {
            String n = p.getFileName().toString().replaceAll("\\.properties$", "");
            for (String b : bases) {
                int rank = -1;
                if (n.equals(b)) rank = 0;
                else if (n.equals(b + "_zh")) rank = 1;
                else if (n.equals(b + "_zh_TW")) rank = 2;
                if (rank < 0) continue;
                List<Path> l = byRank.get(rank);
                if (l == null) byRank.put(rank, l = new ArrayList<Path>());
                l.add(p);
            }
        }
        for (List<Path> l : byRank.values()) {
            for (Path p : l) {
                try {
                    messages.load(new StringReader(read(p)));
                } catch (IOException ignored) {
                }
            }
        }
    }

    static final Pattern P_SCRIPTLET = Pattern.compile("<%(?![@=-])([\\s\\S]*?)%>");
    static final Pattern P_EXPR = Pattern.compile("<%=");
    static final Pattern P_JSTR = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    static final Pattern P_DBCODE = Pattern.compile("(prepareCall|prepareStatement|createStatement|executeQuery|executeUpdate)\\s*\\(");
    static final Pattern P_TAG_ACTION = Pattern.compile("(?i)<(?:html|nested|html-el):(?:form|link|rewrite|frame|img|button)\\b[^>]*?\\baction\\s*=\\s*[\"']([^\"'<>]+)[\"']");
    static final Pattern P_DO = Pattern.compile("([A-Za-z0-9_\\-./]*[A-Za-z0-9_])\\.do(?![\\w])");
    static final Pattern P_INC_DIR = Pattern.compile("<%@\\s*include\\s+file\\s*=\\s*[\"']([^\"']+)[\"']");
    static final Pattern P_INC_ACT = Pattern.compile("<jsp:include\\s+page\\s*=\\s*[\"']([^\"'<]+)[\"']");
    static final Pattern P_INC_TILES = Pattern.compile("<tiles:insert\\b[^>]*?\\b(?:page|template)\\s*=\\s*[\"']([^\"'<]+)[\"']");

    /** Index every "/a/b/c.jsp" suffix so config paths match source-tree paths with extra prefixes. */
    void indexJsps() {
        for (Path p : jspFiles) jspEntry(webPath(p)).put("exists", true);
        for (String key : jspInfo.keySet()) {
            int i = 0;
            while ((i = key.indexOf('/', i)) >= 0) {
                String suf = key.substring(i);
                String old = jspSuffix.get(suf);
                jspSuffix.put(suf, old == null || old.equals(key) ? key : "\u0000");
                i++;
            }
        }
    }

    boolean jspExists(String k) {
        Map<String, Object> e = jspInfo.get(k);
        return e != null && Boolean.TRUE.equals(e.get("exists"));
    }

    String resolveJsp(String p) {
        if (p == null || jspExists(p)) return p;
        String k = jspSuffix.get(p.startsWith("/") ? p : "/" + p);
        return k != null && !k.equals("\u0000") ? k : p;
    }

    void parseJsps() {
        indexJsps();
        for (Path p : jspFiles) {
            String t = read(p);
            String wp = webPath(p);
            Map<String, Object> j = jspEntry(wp);
            j.put("file", rel(p));
            j.put("exists", true);
            int scr = 0;
            boolean db = false;
            List<String[]> sps = new ArrayList<String[]>();
            Set<String> tables = new LinkedHashSet<String>();
            Matcher sm = P_SCRIPTLET.matcher(t);
            while (sm.find()) {
                scr++;
                String code = sm.group(1);
                if (P_DBCODE.matcher(code).find()) db = true;
                Matcher qm = P_JSTR.matcher(code);
                while (qm.find()) {
                    String s = qm.group(1).replace("\\\"", "\"");
                    detectSp(s, sps);
                    detectTables(s, tables);
                }
            }
            int ex = 0;
            Matcher em = P_EXPR.matcher(t);
            while (em.find()) ex++;
            j.put("scriptlets", scr);
            j.put("expressions", ex);
            j.put("db", db);
            List<String> spNames = new ArrayList<String>();
            for (String[] s : sps) spNames.add(s[0]);
            j.put("sps", spNames);
            j.put("tables", new ArrayList<String>(tables));

            Set<String> refs = new LinkedHashSet<String>();
            Matcher am = P_TAG_ACTION.matcher(t);
            while (am.find()) {
                String r = normRef(am.group(1));
                refs.add(r.startsWith("/") ? r : "/" + r); // struts tag paths are always module-absolute
            }
            Matcher dm = P_DO.matcher(t);
            while (dm.find()) refs.add(normRef(dm.group(1)));
            List<String> resolved = new ArrayList<String>();
            List<String> unresolved = new ArrayList<String>();
            List<String> relative = new ArrayList<String>();
            for (String r : refs) {
                if (r.isEmpty() || r.equals("/")) continue;
                if (!r.startsWith("/")) {
                    relative.add(r);
                    continue;
                }
                List<ActionDef> hit = matchActions(r);
                if (hit.isEmpty()) unresolved.add(r);
                for (ActionDef a : hit) if (!resolved.contains(a.id)) resolved.add(a.id);
            }
            j.put("submits", resolved);
            j.put("submitsUnresolved", unresolved);
            if (!relative.isEmpty()) j.put("_relative", relative);

            Set<String> inc = new LinkedHashSet<String>();
            for (Pattern ip : new Pattern[] {P_INC_DIR, P_INC_ACT, P_INC_TILES}) {
                Matcher im = ip.matcher(t);
                while (im.find()) {
                    String v = im.group(1).trim();
                    if (v.contains("<%") || v.contains("${")) continue;
                    inc.add(resolveJsp(resolveRelative(wp, v)));
                }
            }
            j.put("includes", new ArrayList<String>(inc));
        }
    }

    Map<String, Object> jspEntry(String wp) {
        Map<String, Object> j = jspInfo.get(wp);
        if (j == null) {
            j = new LinkedHashMap<String, Object>();
            j.put("exists", false);
            j.put("forwardedFrom", new ArrayList<Map<String, Object>>());
            jspInfo.put(wp, j);
        }
        return j;
    }

    static String normRef(String r) {
        String s = r.trim();
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        q = s.indexOf('#');
        if (q >= 0) s = s.substring(0, q);
        if (s.endsWith(".do")) s = s.substring(0, s.length() - 3);
        while (s.startsWith("../") || s.startsWith("./")) s = s.substring(s.indexOf('/') + 1);
        return s;
    }

    /**
     * Relative URLs like "save.do" are resolved against the URL the browser is on,
     * which is the Action that forwarded to this JSP - not the JSP's own folder.
     */
    @SuppressWarnings("unchecked")
    void resolveRelativeRefs() {
        for (Map<String, Object> j : jspInfo.values()) {
            List<String> rel = (List<String>) j.remove("_relative");
            if (rel == null) continue;
            List<String> resolved = (List<String>) j.get("submits");
            List<String> unresolved = (List<String>) j.get("submitsUnresolved");
            Set<String> dirs = new LinkedHashSet<String>();
            for (Map<String, Object> f : (List<Map<String, Object>>) j.get("forwardedFrom")) {
                ActionDef a = findAction((String) f.get("action"));
                if (a != null) dirs.add(a.fullPath.substring(0, a.fullPath.lastIndexOf('/') + 1));
            }
            for (String r : rel) {
                ActionDef hit = null;
                for (String d : dirs) {
                    hit = actionByFull.get(d + r);
                    if (hit != null) break;
                }
                if (hit == null) {
                    List<ActionDef> tail = new ArrayList<ActionDef>();
                    for (ActionDef a : actions) if (a.fullPath.endsWith("/" + r)) tail.add(a);
                    if (tail.size() == 1) hit = tail.get(0);
                }
                if (hit == null) addU(unresolved, r);
                else addU(resolved, hit.id);
            }
        }
    }

    ActionDef findAction(String id) {
        for (ActionDef a : actions) if (a.id.equals(id)) return a;
        return null;
    }

    List<ActionDef> matchActions(String ref) {
        List<ActionDef> r = new ArrayList<ActionDef>();
        ActionDef a = actionByFull.get(ref);
        if (a != null) {
            r.add(a);
            return r;
        }
        for (ActionDef x : actions) if (x.path.equals(ref)) r.add(x);
        if (!r.isEmpty()) return r;
        ActionDef best = null;
        for (ActionDef x : actions) {
            if (ref.endsWith(x.fullPath) && ref.length() > x.fullPath.length()
                    && ref.charAt(ref.length() - x.fullPath.length() - 1) != '_'
                    && (best == null || x.fullPath.length() > best.fullPath.length())) best = x;
        }
        if (best != null) r.add(best);
        return r;
    }

    static String resolveRelative(String base, String v) {
        if (v.startsWith("/")) return v;
        String dir = base.substring(0, base.lastIndexOf('/') + 1);
        LinkedList<String> parts = new LinkedList<String>();
        for (String s : (dir + v).split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else {
                parts.add(s);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String s : parts) sb.append('/').append(s);
        return sb.toString();
    }

    // ------------------------------------------------------------------ build output

    String urlOf(String fullPath) {
        String pat = actionPattern;
        if (pat.startsWith("*.")) return fullPath + pat.substring(1);
        if (pat.endsWith("/*")) return pat.substring(0, pat.length() - 2) + fullPath;
        return fullPath + ".do";
    }

    List<String> tilesJsps(String name, boolean ownOnly, int depth) {
        List<String> r = new ArrayList<String>();
        TileDef t = tiles.get(name);
        if (t == null || depth > 10) return r;
        if (!ownOnly) {
            if (t.ext != null) for (String s : tilesJsps(t.ext, false, depth + 1)) if (!r.contains(s)) r.add(s);
            String tp = resolveJsp(t.path);
            if (tp != null && !r.contains(tp)) r.add(0, tp);
        }
        for (String[] put : t.puts) {
            String v = put[1];
            if (v == null || v.isEmpty()) continue;
            if (tiles.containsKey(v)) {
                for (String s : tilesJsps(v, false, depth + 1)) if (!r.contains(s)) r.add(s);
            } else if (v.startsWith("/")) {
                String rv = resolveJsp(v);
                if (!r.contains(rv)) r.add(rv);
            }
        }
        return r;
    }

    Map<String, Object> target(String module, String path) {
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        if (path == null || path.isEmpty()) {
            t.put("kind", "none");
            return t;
        }
        t.put("value", path);
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.endsWith(".do") || (actionPattern.endsWith("/*") && p.startsWith(actionPattern.substring(0, actionPattern.length() - 2) + "/"))) {
            String ap = p.endsWith(".do") ? p.substring(0, p.length() - 3) : p.substring(actionPattern.length() - 2);
            ActionDef a = actionByFull.get(ap);
            if (a == null && !module.isEmpty()) a = actionByFull.get(module + ap);
            t.put("kind", "action");
            if (a != null) t.put("action", a.id);
            return t;
        }
        if (tiles.containsKey(path)) {
            t.put("kind", "tiles");
            t.put("jsps", tilesJsps(path, false, 0));
            return t;
        }
        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jsp") || lower.endsWith(".jspf") || lower.endsWith(".html") || lower.endsWith(".htm")) {
            String jp = null;
            if (!module.isEmpty()) {
                String mr = resolveJsp(module + p);
                if (jspExists(mr)) jp = mr;
            }
            if (jp == null) jp = resolveJsp(p);
            t.put("kind", "jsp");
            t.put("jsp", jp);
            Map<String, Object> e = jspInfo.get(jp);
            t.put("exists", e != null && Boolean.TRUE.equals(e.get("exists")));
            return t;
        }
        t.put("kind", "other");
        return t;
    }

    @SuppressWarnings("unchecked")
    void markForwarded(Map<String, Object> tgt, ActionDef a, String fwdName, String tilesName) {
        String kind = (String) tgt.get("kind");
        List<String> jsps = new ArrayList<String>();
        if ("jsp".equals(kind)) jsps.add((String) tgt.get("jsp"));
        else if ("tiles".equals(kind)) jsps.addAll(tilesJsps((String) tgt.get("value"), true, 0));
        for (String jp : jsps) {
            Map<String, Object> e = jspEntry(jp);
            List<Map<String, Object>> l = (List<Map<String, Object>>) e.get("forwardedFrom");
            Map<String, Object> o = new LinkedHashMap<String, Object>();
            o.put("action", a.id);
            o.put("forward", fwdName);
            if ("tiles".equals(kind)) o.put("tiles", tgt.get("value"));
            l.add(o);
        }
    }

    Map<String, Object> build() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> actionOut = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> spOut = new TreeMap<String, Map<String, Object>>();
        Set<String> actionClasses = new HashSet<String>();
        int methodCount = 0;

        // classes referenced by actions but missing from the sources: look them up in jars once
        Set<String> missing = new HashSet<String>();
        for (ActionDef a : actions) {
            String c = actionClass(a);
            if (c != null && !classes.containsKey(c)) missing.add(c);
        }
        scanJars(missing);

        int done = 0;
        for (ActionDef a : actions) {
            if (++done % 200 == 0) log("Tracing actions " + done + " / " + actions.size());
            Map<String, Object> o = new LinkedHashMap<String, Object>();
            o.put("id", a.id);
            o.put("module", a.module);
            o.put("path", a.path);
            o.put("fullPath", a.fullPath);
            o.put("url", urlOf(a.fullPath));
            o.put("configFile", a.file);
            o.put("configLine", a.line);
            o.put("registered", a.registered);
            o.put("type", a.type);

            boolean proxy = isProxy(a);
            String clsName = actionClass(a);
            if (proxy && beanClass(a) == null) warn("bean-missing", "action", a.id);
            o.put("proxy", proxy);
            JClass jc = clsName == null ? null : classes.get(clsName);
            o.put("cls", clsName);
            if (jc != null) {
                o.put("clsFile", jc.file);
                o.put("clsLine", jc.line);
                actionClasses.add(jc.fqn);
            }

            String kind;
            String base = null;
            if (a.type == null && a.fwdAttr != null) kind = "forward";
            else if (a.type == null && a.includeAttr != null) kind = "include";
            else if (clsName == null) kind = "unknown";
            else if (clsName.startsWith("org.apache.struts.actions.")) {
                kind = "builtin";
                base = clsName.substring(clsName.lastIndexOf('.') + 1);
            } else if (clsName.endsWith("PageForwardAction") || clsName.endsWith(".ForwardAction")) {
                kind = "forward";
                base = clsName.substring(clsName.lastIndexOf('.') + 1);
                if (jarOfClass.containsKey(clsName)) o.put("jar", jarOfClass.get(clsName));
            } else if (jc == null && jarOfClass.containsKey(clsName)) {
                kind = "jar";
                o.put("jar", jarOfClass.get(clsName));
            } else if (jc == null) {
                kind = "missing";
                if (!clsName.startsWith("org.springframework")) warn("class-missing", "action", a.id, "cls", clsName);
            } else {
                JClass top = jc;
                Set<String> seen = new HashSet<String>();
                while (top.superFqn != null && classes.containsKey(top.superFqn) && seen.add(top.fqn)) top = classes.get(top.superFqn);
                base = top.superRaw == null ? "Object" : stripGenerics(top.superRaw);
                base = base.substring(base.lastIndexOf('.') + 1);
                if (base.equals("LookupDispatchAction")) kind = "lookup";
                else if (base.equals("MappingDispatchAction")) kind = "mapping";
                else if (base.equals("EventDispatchAction")) kind = "event";
                else if (base.endsWith("DispatchAction")) kind = "dispatch";
                else kind = "plain";
            }
            o.put("kind", kind);
            o.put("base", base);

            o.put("form", a.form);
            if (a.form != null) {
                FormBean fb = forms.get(a.module + "|" + a.form);
                if (fb == null) fb = forms.get("|" + a.form);
                if (fb == null) {
                    warn("form-missing", "action", a.id, "form", a.form);
                } else {
                    o.put("formType", fb.type);
                    o.put("formDyna", fb.dyna);
                    JClass fc = classes.get(fb.type);
                    if (fc != null) o.put("formFile", fc.file);
                    if (!fb.props.isEmpty()) {
                        List<String> props = new ArrayList<String>();
                        for (String[] pp : fb.props) props.add(pp[0] + " : " + pp[1]);
                        o.put("formProps", props);
                    }
                }
                o.put("scope", a.scope);
                o.put("scopeDefault", a.scopeDefault);
                o.put("validate", a.validate);
                List<String[]> vr = validations.get(a.form);
                if (vr != null) {
                    List<Map<String, Object>> vl = new ArrayList<Map<String, Object>>();
                    for (String[] v : vr) {
                        Map<String, Object> vm = new LinkedHashMap<String, Object>();
                        vm.put("field", v[0]);
                        vm.put("depends", v[1]);
                        vl.add(vm);
                    }
                    o.put("validation", vl);
                }
            }
            o.put("input", a.input);
            if (a.input != null) o.put("inputTarget", target(a.module, a.input));
            o.put("parameter", a.parameter);
            if (a.fwdAttr != null) {
                Map<String, Object> ft = target(a.module, a.fwdAttr);
                o.put("forwardAttr", ft);
                markForwarded(ft, a, "(forward)", null);
            }
            if ("builtin".equals(kind) && a.parameter != null && "ForwardAction".equals(base)) {
                Map<String, Object> ft = target(a.module, a.parameter);
                o.put("forwardAttr", ft);
                markForwarded(ft, a, "(ForwardAction)", null);
            }

            Map<String, Forward> local = new LinkedHashMap<String, Forward>();
            for (Forward f : a.forwards) local.put(f.name, f);
            Map<String, Forward> global = new LinkedHashMap<String, Forward>();
            List<Forward> gl = globalForwards.get(a.module);
            if (gl != null) for (Forward f : gl) global.put(f.name, f);

            Map<String, List<String>> usedBy = new LinkedHashMap<String, List<String>>();
            List<Map<String, Object>> methodsOut = new ArrayList<Map<String, Object>>();
            if (jc != null) {
                List<JMethod> sel = actionMethods(jc, kind, a.parameter);
                if ("plain".equals(kind) && !sel.isEmpty() && !sel.get(0).name.equals("execute")) {
                    kind = "custom";
                    o.put("kind", kind);
                }
                Map<String, String> labels = new HashMap<String, String>();
                if ("lookup".equals(kind)) {
                    for (JClass lc : lineage(jc.fqn)) {
                        for (JMethod lm : lc.methods) {
                            if (!lm.name.equals("getKeyMethodMap")) continue;
                            analyze(lm);
                            for (String[] kv : lm.keyMap) {
                                String lbl = messages.getProperty(kv[0]);
                                labels.put(kv[1], lbl != null ? lbl + "  (" + kv[0] + ")" : kv[0]);
                            }
                        }
                    }
                }
                for (JMethod jm : sel) {
                    methodCount++;
                    analyze(jm);
                    Map<String, Object> mo = new LinkedHashMap<String, Object>();
                    mo.put("name", jm.name);
                    mo.put("file", jm.owner.file);
                    mo.put("line", jm.line);
                    if (jm.owner != jc) mo.put("inherited", jm.owner.simple);
                    if (labels.containsKey(jm.name)) mo.put("label", labels.get(jm.name));
                    String hint = urlOf(a.fullPath);
                    if (("dispatch".equals(kind) || "custom".equals(kind)) && a.parameter != null) hint += "?" + a.parameter + "=" + jm.name;
                    mo.put("urlHint", hint);

                    List<Map<String, Object>> fw = new ArrayList<Map<String, Object>>();
                    for (String fn : jm.fwdNames) {
                        Map<String, Object> f = new LinkedHashMap<String, Object>();
                        f.put("name", fn);
                        Forward def = local.get(fn);
                        String sc = "local";
                        if (def == null) {
                            def = global.get(fn);
                            sc = "global";
                        }
                        if (def == null) {
                            sc = "undefined";
                            warn("fwd-undefined", "action", a.id, "method", jm.name, "forward", fn);
                        } else {
                            f.put("target", target(def.contextRelative ? "" : a.module, def.path));
                        }
                        f.put("scope", sc);
                        fw.add(f);
                        List<String> ub = usedBy.get(fn);
                        if (ub == null) usedBy.put(fn, ub = new ArrayList<String>());
                        addU(ub, jm.name);
                    }
                    mo.put("forwards", fw);
                    List<Map<String, Object>> fp = new ArrayList<Map<String, Object>>();
                    for (String pth : jm.fwdPaths) {
                        Map<String, Object> f = new LinkedHashMap<String, Object>();
                        f.put("path", pth);
                        Map<String, Object> tg = target(a.module, pth);
                        f.put("target", tg);
                        markForwarded(tg, a, "new ActionForward", null);
                        fp.add(f);
                    }
                    mo.put("paths", fp);

                    Reach r = reach(jm, jc.fqn);
                    List<Map<String, Object>> sps = new ArrayList<Map<String, Object>>();
                    for (Map.Entry<String, Object[]> e : r.sps.entrySet()) {
                        Map<String, Object> so = new LinkedHashMap<String, Object>();
                        so.put("name", e.getKey());
                        so.put("kind", e.getValue()[0]);
                        @SuppressWarnings("unchecked")
                        List<JMethod> path = (List<JMethod>) e.getValue()[1];
                        so.put("via", viaJson(path));
                        sps.add(so);
                        if ("dynamic".equals(e.getValue()[0])) {
                            JMethod last = path.get(path.size() - 1);
                            warn("sp-dynamic", "action", a.id, "method", jm.name, "sp", e.getKey(),
                                    "file", last.owner.file, "line", String.valueOf(last.line));
                        }
                        Map<String, Object> spo = spOut.get(e.getKey());
                        if (spo == null) {
                            spo = new LinkedHashMap<String, Object>();
                            spo.put("kind", e.getValue()[0]);
                            spo.put("callers", new ArrayList<Map<String, Object>>());
                            spo.put("jsps", new ArrayList<String>());
                            spOut.put(e.getKey(), spo);
                        }
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> callers = (List<Map<String, Object>>) spo.get("callers");
                        Map<String, Object> co = new LinkedHashMap<String, Object>();
                        co.put("action", a.id);
                        co.put("method", jm.name);
                        co.put("via", viaJson(path));
                        callers.add(co);
                    }
                    mo.put("sps", sps);
                    mo.put("tables", new ArrayList<String>(r.tables));
                    if (r.truncated) mo.put("truncated", true);
                    Map<String, Object> sess = new LinkedHashMap<String, Object>();
                    sess.put("set", jm.sessionSet);
                    sess.put("get", jm.sessionGet);
                    mo.put("session", sess);
                    mo.put("requestSet", jm.requestSet);
                    methodsOut.add(mo);
                }
            }
            o.put("methods", methodsOut);

            List<Map<String, Object>> fwOut = new ArrayList<Map<String, Object>>();
            for (Forward f : a.forwards) {
                Map<String, Object> fo = new LinkedHashMap<String, Object>();
                fo.put("name", f.name);
                fo.put("path", f.path);
                fo.put("redirect", f.redirect);
                Map<String, Object> tg = target(f.contextRelative ? "" : a.module, f.path);
                fo.put("target", tg);
                List<String> ub = usedBy.get(f.name);
                fo.put("usedBy", ub == null ? new ArrayList<String>() : ub);
                fwOut.add(fo);
                markForwarded(tg, a, f.name, null);
                if ((("jsp".equals(tg.get("kind")) && !Boolean.TRUE.equals(tg.get("exists")))) || "other".equals(tg.get("kind"))) {
                    warn("target-missing", "action", a.id, "forward", f.name, "path", f.path);
                }
            }
            o.put("forwards", fwOut);
            List<Map<String, Object>> exOut = new ArrayList<Map<String, Object>>();
            for (String[] ex : a.exceptions) {
                Map<String, Object> eo = new LinkedHashMap<String, Object>();
                eo.put("type", ex[0]);
                eo.put("path", ex[1]);
                eo.put("key", ex[2]);
                exOut.add(eo);
            }
            o.put("exceptions", exOut);
            actionOut.add(o);
        }

        resolveRelativeRefs();

        // jsp -> action sources, and jsp sp callers
        Map<String, List<String>> sources = new HashMap<String, List<String>>();
        for (Map.Entry<String, Map<String, Object>> e : jspInfo.entrySet()) {
            @SuppressWarnings("unchecked")
            List<String> sub = (List<String>) e.getValue().get("submits");
            if (sub != null) {
                for (String id : sub) {
                    List<String> l = sources.get(id);
                    if (l == null) sources.put(id, l = new ArrayList<String>());
                    addU(l, e.getKey());
                }
            }
            @SuppressWarnings("unchecked")
            List<String> sps = (List<String>) e.getValue().get("sps");
            if (sps != null) {
                for (String sp : sps) {
                    Map<String, Object> spo = spOut.get(sp);
                    if (spo == null) {
                        spo = new LinkedHashMap<String, Object>();
                        spo.put("kind", "call");
                        spo.put("callers", new ArrayList<Map<String, Object>>());
                        spo.put("jsps", new ArrayList<String>());
                        spOut.put(sp, spo);
                    }
                    @SuppressWarnings("unchecked")
                    List<String> jl = (List<String>) spo.get("jsps");
                    addU(jl, e.getKey());
                }
            }
        }
        for (Map<String, Object> o : actionOut) {
            List<String> l = sources.get(o.get("id"));
            o.put("sources", l == null ? new ArrayList<String>() : l);
        }

        // tiles
        Map<String, Object> tilesOut = new LinkedHashMap<String, Object>();
        for (TileDef t : tiles.values()) {
            Map<String, Object> to = new LinkedHashMap<String, Object>();
            to.put("path", t.path);
            to.put("extends", t.ext);
            List<Map<String, Object>> puts = new ArrayList<Map<String, Object>>();
            for (String[] p : t.puts) {
                Map<String, Object> po = new LinkedHashMap<String, Object>();
                po.put("name", p[0]);
                po.put("value", p[1]);
                puts.add(po);
            }
            to.put("puts", puts);
            to.put("jsps", tilesJsps(t.name, false, 0));
            tilesOut.put(t.name, to);
        }

        // global forwards
        Map<String, Object> gfOut = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, List<Forward>> e : globalForwards.entrySet()) {
            List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
            for (Forward f : e.getValue()) {
                Map<String, Object> fo = new LinkedHashMap<String, Object>();
                fo.put("name", f.name);
                fo.put("path", f.path);
                fo.put("target", target(f.contextRelative ? "" : e.getKey(), f.path));
                l.add(fo);
            }
            gfOut.put(e.getKey(), l);
        }

        // framework jars
        List<String> fw = new ArrayList<String>();
        Pattern fwp = Pattern.compile("(?i)^(struts|spring|tiles|ojdbc|classes12|hibernate|ibatis|mybatis|log4j|commons-fileupload|commons-collections|commons-beanutils|jasperreports|itext|poi|xstream|jackson|fastjson).*\\.jar$");
        for (String j : jarNames) if (fwp.matcher(j).matches() && !fw.contains(j)) fw.add(j);
        Collections.sort(fw);

        int sessionForms = 0;
        for (ActionDef a : actions) if (a.form != null && "session".equals(a.scope)) sessionForms++;
        int jspDb = 0, jspScr = 0;
        for (Map<String, Object> j : jspInfo.values()) {
            if (Boolean.TRUE.equals(j.get("db"))) jspDb++;
            Object s = j.get("scriptlets");
            if (s instanceof Integer && (Integer) s > 0) jspScr++;
        }

        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("tool", "StrutsMapper " + TOOL_VERSION);
        meta.put("root", root.toString());
        meta.put("generatedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
        meta.put("actionPattern", actionPattern);
        Map<String, Object> counts = new LinkedHashMap<String, Object>();
        counts.put("actions", actions.size());
        counts.put("actionClasses", actionClasses.size());
        counts.put("methods", methodCount);
        counts.put("sps", spOut.size());
        counts.put("jsps", jspFiles.size());
        counts.put("jspScriptlet", jspScr);
        counts.put("jspDb", jspDb);
        counts.put("forms", forms.size());
        counts.put("sessionForms", sessionForms);
        counts.put("javaClasses", classes.size());
        counts.put("tiles", tiles.size());
        counts.put("warnings", warnings.size());
        meta.put("counts", counts);
        meta.put("jars", fw);
        meta.put("configFiles", configFiles);
        meta.put("filters", filters);
        meta.put("listeners", listeners);
        meta.put("plugins", new ArrayList<String>(plugins));

        data.put("meta", meta);
        data.put("actions", actionOut);
        data.put("sps", spOut);
        data.put("jsps", jspInfo);
        data.put("tiles", tilesOut);
        data.put("globalForwards", gfOut);
        data.put("warnings", warnings);
        return data;
    }

    boolean isProxy(ActionDef a) {
        return (a.type != null && a.type.endsWith("DelegatingActionProxy")) || delegatingModules.contains(a.module);
    }

    final Map<String, String> beanCache = new HashMap<String, String>();

    /** Spring bean class for a DelegatingActionProxy action (bean name = module prefix + path). */
    String beanClass(ActionDef a) {
        if (beanCache.containsKey(a.id)) return beanCache.get(a.id);
        String bc = springBeans.get(a.fullPath);
        if (bc == null) bc = springBeans.get(a.path);
        if (bc == null) {
            // module could not be determined: accept a unique bean whose name ends with "/<anything>" + path
            String found = null;
            int n = 0;
            for (Map.Entry<String, String> e : springBeans.entrySet()) {
                String k = e.getKey();
                if (k.length() > a.path.length() && k.endsWith(a.path) && k.startsWith("/")) {
                    found = e.getValue();
                    n++;
                }
            }
            if (n == 1) bc = found;
        }
        beanCache.put(a.id, bc);
        return bc;
    }

    String actionClass(ActionDef a) {
        if (isProxy(a)) {
            String bc = beanClass(a);
            if (bc != null) return bc;
        }
        return a.type;
    }

    void scanJars(Set<String> wanted) {
        if (wanted.isEmpty() || jarPaths.isEmpty()) return;
        log("Looking up " + wanted.size() + " classes in " + jarPaths.size() + " jars");
        for (Path jar : jarPaths) {
            if (jarOfClass.size() == wanted.size()) break;
            java.util.zip.ZipFile z = null;
            try {
                z = new java.util.zip.ZipFile(jar.toFile());
                for (String c : wanted) {
                    if (jarOfClass.containsKey(c)) continue;
                    if (z.getEntry(c.replace('.', '/') + ".class") != null) jarOfClass.put(c, jar.getFileName().toString());
                }
            } catch (IOException e) {
                // not a readable jar, skip
            } finally {
                if (z != null) try { z.close(); } catch (IOException ignored) { }
            }
        }
    }

    List<JMethod> actionMethods(JClass jc, String kind, String parameter) {
        List<JMethod> sig = new ArrayList<JMethod>();
        Set<String> names = new HashSet<String>();
        Set<String> seen = new HashSet<String>();
        for (JClass c = jc; c != null && seen.add(c.fqn); c = c.superFqn == null ? null : classes.get(c.superFqn)) {
            for (JMethod m : c.methods) {
                if (m.bodyStart < 0 || m.params == null) continue;
                if (!m.params.contains("ActionMapping") || !m.params.contains("HttpServletRequest")) continue;
                if (names.add(m.name)) sig.add(m);
            }
        }
        List<JMethod> out = new ArrayList<JMethod>();
        if ("dispatch".equals(kind) || "lookup".equals(kind) || "event".equals(kind)) {
            for (JMethod m : sig) if (!m.name.equals("execute") || m.owner == jc) out.add(m);
        } else if ("mapping".equals(kind)) {
            for (JMethod m : sig) if (parameter == null || m.name.equals(parameter)) out.add(m);
        } else {
            JMethod exec = null;
            List<JMethod> others = new ArrayList<JMethod>();
            for (JMethod m : sig) {
                if (m.name.equals("execute")) exec = m;
                else if (m.owner == jc) others.add(m);
            }
            if (!others.isEmpty() && (exec == null || exec.owner != jc)) out.addAll(others);
            else if (exec != null) out.add(exec);
        }
        return out;
    }

    void warn(String type, String... kv) {
        Map<String, Object> w = new LinkedHashMap<String, Object>();
        w.put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) w.put(kv[i], kv[i + 1]);
        warnings.add(w);
    }

    // ------------------------------------------------------------------ json

    static String toJson(Object o) {
        StringBuilder sb = new StringBuilder(1 << 16);
        writeJson(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static void writeJson(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            jstr(sb, (String) o);
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                jstr(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object x : (Collection<Object>) o) {
                if (!first) sb.append(',');
                first = false;
                writeJson(sb, x);
            }
            sb.append(']');
        } else {
            jstr(sb, String.valueOf(o));
        }
    }

    static void jstr(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;
                case '>': sb.append("\\u003e"); break;
                case '&': sb.append("\\u0026"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
