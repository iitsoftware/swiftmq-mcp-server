package com.swiftmq.mcp.catalog;

import com.swiftmq.mcp.guard.DeniedContexts;
import com.swiftmq.mcp.guard.Secrets;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Builds the {@link Catalog} from the swiftlets' {@code config.xml} files of the CE, UR and HA distributions:
 * {@code kernel/<swiftlet>/config.xml} and the extension swiftlets in
 * {@code optional-swiftlets/extension/<swiftlet>/config.xml}. Each distribution becomes one edition layer.
 * Denied swiftlets are left out. Fails if a property name looks like a secret but isn't covered by {@link Secrets}.
 * <p>
 * Usage: {@code CatalogGenerator <ce dist> <ur dist> <ha dist> <output json>} (see the {@code gen-catalog} Maven profile).
 */
public final class CatalogGenerator {

    private CatalogGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: CatalogGenerator <ce dist> <ur dist> <ha dist> <output json>");
            System.exit(1);
        }
        Catalog catalog = generate(Map.of(Edition.CE, new File(args[0]), Edition.UR, new File(args[1]), Edition.HA, new File(args[2])));
        Catalog.MAPPER.writerWithDefaultPrettyPrinter().writeValue(new File(args[3]), catalog);
        System.err.println("Wrote " + args[3] + " (release " + catalog.release() + "; swiftlets: "
                + Arrays.stream(Edition.values()).map(e -> e.key() + " " + catalog.swiftlets(e).size()).toList() + ")");
    }

    /** @param distributions the root directory of each edition's router distribution */
    public static Catalog generate(Map<Edition, File> distributions) throws IOException {
        Map<String, Map<String, CatalogNode>> layers = new TreeMap<>();
        Set<String> releases = new TreeSet<>();
        for (Edition edition : Edition.values()) {
            File dist = distributions.get(edition);
            if (dist == null)
                throw new IllegalArgumentException("No distribution for edition " + edition.key());
            Map<String, CatalogNode> swiftlets = new TreeMap<>();
            for (File config : configFiles(dist)) {
                Element swiftlet = parse(config);
                String name = swiftlet.getAttribute("name");
                if (DeniedContexts.isFixedDenied(name))
                    continue;
                releases.add(swiftlet.getAttribute("release"));
                Element configuration = single(swiftlet, "configuration");
                swiftlets.put(name, entity(configuration, swiftlet.getAttribute("displayname"), swiftlet.getAttribute("description"), name));
            }
            if (swiftlets.isEmpty())
                throw new IllegalStateException("No swiftlet config.xml found under " + dist);
            layers.put(edition.key(), swiftlets);
        }
        if (releases.size() != 1)
            throw new IllegalStateException("Expected exactly one release, found " + releases);
        return new Catalog(releases.iterator().next(), layers);
    }

    private static List<File> configFiles(File dist) {
        if (!dist.isDirectory())
            throw new IllegalArgumentException("Not a directory: " + dist);
        List<File> files = new ArrayList<>();
        for (String dir : List.of("kernel", "optional-swiftlets/extension")) {
            File[] swiftlets = new File(dist, dir).listFiles(File::isDirectory);
            if (swiftlets != null)
                Arrays.stream(swiftlets).map(d -> new File(d, "config.xml")).filter(File::isFile).forEach(files::add);
        }
        return files.stream().sorted().toList();
    }

    private static CatalogNode entity(Element e, String display, String description, String path) {
        Map<String, PropertyDef> properties = new LinkedHashMap<>();
        Map<String, CatalogNode> entities = new LinkedHashMap<>();
        Map<String, CatalogNode> lists = new LinkedHashMap<>();
        for (Element child : children(e)) {
            String name = child.getAttribute("name");
            String childPath = path + "/" + name;
            switch (child.getTagName()) {
                case "property" -> properties.put(name, property(child, childPath));
                case "entity" -> entities.put(name, entity(child, attr(child, "display"), attr(child, "description"), childPath));
                case "entitylist" -> lists.put(name, list(child, childPath));
                default -> { /* entitytemplate is handled by list(); nothing else carries metadata */ }
            }
        }
        return new CatalogNode(CatalogNode.Kind.ENTITY, display, description, nullIfEmpty(properties), nullIfEmpty(entities), nullIfEmpty(lists), null, null);
    }

    /** Empty maps aren't written to the JSON, so they must be null here for a generated catalog to equal a loaded one. */
    private static <V> Map<String, V> nullIfEmpty(Map<String, V> map) {
        return map.isEmpty() ? null : map;
    }

    private static CatalogNode list(Element e, String path) {
        Element template = single(e, "entitytemplate");
        CatalogNode templateNode = entity(template, attr(template, "display"), attr(template, "description"), path + "/*");
        String dynamic = attr(e, "dynamic-properties");
        List<String> dynamicProperties = dynamic == null ? null : List.of(dynamic.trim().split("\\s+"));
        return new CatalogNode(CatalogNode.Kind.LIST, attr(e, "display"), attr(e, "description"), null, null, null, templateNode, dynamicProperties);
    }

    private static PropertyDef property(Element p, String path) {
        String name = p.getAttribute("name");
        if (Secrets.isUnreviewedSuspicious(name))
            throw new IllegalStateException("Property '" + path + "' looks like a secret but is not covered by Secrets; review it");
        String type = attr(p, "type");
        String choice = attr(p, "choice");
        return new PropertyDef(
                type == null ? "String" : type.substring(type.lastIndexOf('.') + 1),
                attr(p, "display"),
                attr(p, "description"),
                attr(p, "default"),
                attr(p, "min"),
                attr(p, "max"),
                choice == null ? null : List.of(choice.trim().split("\\s+")),
                Boolean.parseBoolean(attr(p, "read-only")),
                Boolean.parseBoolean(attr(p, "mandatory")),
                Boolean.parseBoolean(attr(p, "reboot-required")),
                Secrets.isSecret(name));
    }

    private static Element parse(File file) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return dbf.newDocumentBuilder().parse(file).getDocumentElement();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Cannot parse " + file + ": " + e.getMessage(), e);
        }
    }

    private static List<Element> children(Element e) {
        List<Element> result = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element child)
                result.add(child);
        return result;
    }

    private static Element single(Element parent, String tag) {
        List<Element> matches = children(parent).stream().filter(c -> c.getTagName().equals(tag)).toList();
        if (matches.size() != 1)
            throw new IllegalStateException("Expected one <" + tag + "> in <" + parent.getTagName() + " name='" + parent.getAttribute("name") + "'>, found " + matches.size());
        return matches.get(0);
    }

    private static String attr(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name) : null;
    }
}
