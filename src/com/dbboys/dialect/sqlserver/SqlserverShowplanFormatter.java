package com.dbboys.dialect.sqlserver;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders SQL Server ShowPlanXML as an indented operator tree, similar to
 * {@code SET SHOWPLAN_TEXT} / the SSMS text view, with row/IO/CPU estimates
 * and missing-index hints. Falls back to the raw XML when parsing fails.
 */
final class SqlserverShowplanFormatter {

    private SqlserverShowplanFormatter() {
    }

    static String format(String xml) {
        if (xml == null || xml.isBlank()) {
            return "";
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            try {
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // hardening not supported by this parser; input is server-generated
            }
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            if (root == null || !"ShowPlanXML".equals(localName(root))) {
                return xml;
            }
            StringBuilder out = new StringBuilder();
            NodeList statements = root.getElementsByTagNameNS("*", "Statements");
            for (int i = 0; i < statements.getLength(); i++) {
                for (Element stmt : childElements((Element) statements.item(i))) {
                    formatStatement(stmt, out);
                }
            }
            return out.length() == 0 ? xml : out.toString();
        } catch (Exception e) {
            return xml;
        }
    }

    private static void formatStatement(Element stmt, StringBuilder out) {
        String text = stmt.getAttribute("StatementText");
        if (text != null && !text.isBlank()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(text.trim()).append("\n\n");
        }
        NodeList plans = stmt.getElementsByTagNameNS("*", "QueryPlan");
        for (int i = 0; i < plans.getLength(); i++) {
            Element queryPlan = (Element) plans.item(i);
            Element rootRelOp = firstChild(queryPlan, "RelOp");
            if (rootRelOp != null) {
                formatRelOp(rootRelOp, 0, out);
            }
            formatMissingIndexes(queryPlan, out);
        }
    }

    private static void formatRelOp(Element relOp, int depth, StringBuilder out) {
        out.append("    ".repeat(depth)).append("|--").append(relOp.getAttribute("PhysicalOp"));
        String logical = relOp.getAttribute("LogicalOp");
        String physical = relOp.getAttribute("PhysicalOp");
        if (logical != null && !logical.isEmpty() && !logical.equals(physical)) {
            out.append('(').append(logical).append(')');
        }

        List<String> details = new ArrayList<>();
        Element opElement = operatorElement(relOp);
        if (opElement != null) {
            Element object = firstDescendant(opElement, "Object");
            if (object != null) {
                StringBuilder name = new StringBuilder();
                appendAttr(name, object, "Database");
                appendAttr(name, object, "Schema");
                appendAttr(name, object, "Table");
                String index = object.getAttribute("Index");
                if (index != null && !index.isBlank()) {
                    name.append('.').append(index);
                }
                if (name.length() > 0) {
                    details.add("OBJECT:(" + name + ")");
                }
            }
            String seek = scalarStrings(opElement, "SeekPredicates");
            if (!seek.isEmpty()) {
                details.add("SEEK:(" + seek + ")");
            }
            String predicate = scalarStrings(opElement, "Predicate");
            if (!predicate.isEmpty()) {
                details.add("WHERE:(" + predicate + ")");
            }
        }
        appendDetail(details, "rows", relOp.getAttribute("EstimateRows"));
        appendDetail(details, "io", relOp.getAttribute("EstimateIO"));
        appendDetail(details, "cpu", relOp.getAttribute("EstimateCPU"));
        appendDetail(details, "subtree", relOp.getAttribute("EstimatedTotalSubtreeCost"));
        if ("1".equals(relOp.getAttribute("Parallel"))) {
            details.add("parallel");
        }
        String mode = relOp.getAttribute("EstimatedExecutionMode");
        if (mode != null && !mode.isBlank() && !"Row".equals(mode)) {
            details.add("mode=" + mode);
        }
        if (!details.isEmpty()) {
            out.append('(').append(String.join(", ", details)).append(')');
        }
        out.append('\n');

        for (Element child : childElements(relOp)) {
            if ("RelOp".equals(localName(child))) {
                formatRelOp(child, depth + 1, out);
            }
        }
    }

    private static void formatMissingIndexes(Element queryPlan, StringBuilder out) {
        NodeList groups = queryPlan.getElementsByTagNameNS("*", "MissingIndexGroup");
        for (int i = 0; i < groups.getLength(); i++) {
            Element group = (Element) groups.item(i);
            Element index = firstChild(group, "MissingIndex");
            if (index == null) {
                continue;
            }
            StringBuilder line = new StringBuilder("-- Missing index");
            String impact = group.getAttribute("Impact");
            if (impact != null && !impact.isBlank()) {
                line.append(" (impact ").append(impact).append(')');
            }
            line.append(": ");
            StringBuilder name = new StringBuilder();
            appendAttr(name, index, "Database");
            appendAttr(name, index, "Schema");
            appendAttr(name, index, "Table");
            line.append(name);
            for (Element columnGroup : childElements(index)) {
                if (!"ColumnGroup".equals(localName(columnGroup))) {
                    continue;
                }
                line.append(' ').append(columnGroup.getAttribute("Usage")).append('(');
                List<String> columns = new ArrayList<>();
                for (Element column : childElements(columnGroup)) {
                    columns.add(column.getAttribute("Name"));
                }
                line.append(String.join(", ", columns)).append(')');
            }
            out.append(line).append('\n');
        }
    }

    /** First element child that carries the operator specifics (IndexScan, NestedLoops, ...). */
    private static Element operatorElement(Element relOp) {
        for (Element child : childElements(relOp)) {
            String name = localName(child);
            if (!"RelOp".equals(name) && !"OutputList".equals(name)
                    && !"Warnings".equals(name) && !"MemoryFractions".equals(name)) {
                return child;
            }
        }
        return null;
    }

    /** Joins every ScalarOperator ScalarString under the given descendant section. */
    private static String scalarStrings(Element opElement, String sectionName) {
        Element section = firstDescendant(opElement, sectionName);
        if (section == null) {
            return "";
        }
        List<String> strings = new ArrayList<>();
        NodeList scalars = section.getElementsByTagNameNS("*", "ScalarOperator");
        for (int i = 0; i < scalars.getLength(); i++) {
            String value = ((Element) scalars.item(i)).getAttribute("ScalarString");
            if (value != null && !value.isBlank()) {
                strings.add(value);
            }
        }
        return String.join(" AND ", strings);
    }

    private static Element firstDescendant(Element parent, String name) {
        NodeList matches = parent.getElementsByTagNameNS("*", name);
        return matches.getLength() == 0 ? null : (Element) matches.item(0);
    }

    private static Element firstChild(Element parent, String name) {
        for (Element child : childElements(parent)) {
            if (name.equals(localName(child))) {
                return child;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) node);
            }
        }
        return children;
    }

    private static String localName(Element element) {
        String local = element.getLocalName();
        return local != null ? local : element.getTagName();
    }

    private static void appendAttr(StringBuilder target, Element element, String attribute) {
        String value = element.getAttribute(attribute);
        if (value != null && !value.isBlank()) {
            if (target.length() > 0) {
                target.append('.');
            }
            target.append(value);
        }
    }

    private static void appendDetail(List<String> details, String label, String value) {
        if (value != null && !value.isBlank()) {
            details.add(label + "=" + value);
        }
    }
}
