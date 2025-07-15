package javaxt.core.test.webservices;

import java.io.IOException;
import java.io.InputStream;

import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;


public class WSDLTests {

    @Test
    public void parserTest() throws IOException {
        javaxt.webservices.WSDL wsdl = new javaxt.webservices.WSDL(getXML("wsdl/xCurrencies.asmx.xml"));
        Document output = wsdl.getSSD();
        Document expectedOutput = getXML("wsdl/xCurrencies.ssd.xml");

        // Compare the two XML documents
        compareXMLDocuments(output, expectedOutput);
    }

    private void compareXMLDocuments(Document actual, Document expected) {
        Element actualRoot = actual.getDocumentElement();
        Element expectedRoot = expected.getDocumentElement();

        compareElements(actualRoot, expectedRoot, "");
    }

    private void compareElements(Element actual, Element expected, String path) {
        // Compare element names
        Assert.assertEquals("Element name mismatch at " + path,
            expected.getTagName(), actual.getTagName());

        // Compare attributes
        compareAttributes(actual, expected, path);

        // Collect child elements by tag name
        NodeList actualChildren = actual.getChildNodes();
        NodeList expectedChildren = expected.getChildNodes();

        // Build maps of tag name -> list of elements
        java.util.Map<String, java.util.List<Element>> actualMap = new java.util.HashMap<>();
        java.util.Map<String, java.util.List<Element>> expectedMap = new java.util.HashMap<>();
        java.util.List<Node> actualTextNodes = new java.util.ArrayList<>();
        java.util.List<Node> expectedTextNodes = new java.util.ArrayList<>();

        for (int i = 0; i < actualChildren.getLength(); i++) {
            Node node = actualChildren.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                actualMap.computeIfAbsent(node.getNodeName(), k -> new java.util.ArrayList<>()).add((Element) node);
            } else if (node.getNodeType() == Node.TEXT_NODE && !node.getTextContent().trim().isEmpty()) {
                actualTextNodes.add(node);
            }
        }
        for (int i = 0; i < expectedChildren.getLength(); i++) {
            Node node = expectedChildren.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                expectedMap.computeIfAbsent(node.getNodeName(), k -> new java.util.ArrayList<>()).add((Element) node);
            } else if (node.getNodeType() == Node.TEXT_NODE && !node.getTextContent().trim().isEmpty()) {
                expectedTextNodes.add(node);
            }
        }

        // Check for missing and extra tags
        for (String tag : expectedMap.keySet()) {
            if (!actualMap.containsKey(tag)) {
                Assert.fail("Missing tag '" + tag + "' at " + path);
            }
        }
        for (String tag : actualMap.keySet()) {
            if (!expectedMap.containsKey(tag)) {
                Assert.fail("Extra tag '" + tag + "' at " + path);
            }
        }

        // Compare elements by tag name and index
        for (String tag : expectedMap.keySet()) {
            java.util.List<Element> expectedList = expectedMap.get(tag);
            java.util.List<Element> actualList = actualMap.get(tag);
            if (actualList == null) continue; // already failed above if missing
            Assert.assertEquals("Number of '" + tag + "' elements mismatch at " + path,
                expectedList.size(), actualList.size());
            for (int i = 0; i < expectedList.size(); i++) {
                compareElements(actualList.get(i), expectedList.get(i), path + "/" + tag + "[" + i + "]");
            }
        }

        // Compare text nodes (order-sensitive)
        Assert.assertEquals("Number of text nodes mismatch at " + path,
            expectedTextNodes.size(), actualTextNodes.size());
        for (int i = 0; i < expectedTextNodes.size(); i++) {
            String expectedText = expectedTextNodes.get(i).getTextContent().trim();
            String actualText = actualTextNodes.get(i).getTextContent().trim();
            Assert.assertEquals("Text content mismatch at " + path + "/text()[" + i + "]",
                expectedText, actualText);
        }
    }

    private void compareAttributes(Element actual, Element expected, String path) {
        NamedNodeMap expectedAttrs = expected.getAttributes();
        NamedNodeMap actualAttrs = actual.getAttributes();

        Assert.assertEquals("Number of attributes mismatch at " + path,
            expectedAttrs.getLength(), actualAttrs.getLength());

        for (int i = 0; i < expectedAttrs.getLength(); i++) {
            Attr expectedAttr = (Attr) expectedAttrs.item(i);
            String attrName = expectedAttr.getName();
            String expectedValue = expectedAttr.getValue();

            Attr actualAttr = (Attr) actualAttrs.getNamedItem(attrName);
            Assert.assertNotNull("Missing attribute '" + attrName + "' at " + path, actualAttr);

            String actualValue = actualAttr.getValue();
            Assert.assertEquals("Attribute value mismatch for '" + attrName + "' at " + path,
                expectedValue, actualValue);
        }
    }


    private Document getXML(String filename) throws IOException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(filename)) {
            return javaxt.xml.DOM.createDocument(inputStream);
        }
    }
}
