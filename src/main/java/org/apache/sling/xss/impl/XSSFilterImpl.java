/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one or
 * more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the
 * Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by
 * applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 ******************************************************************************/
package org.apache.sling.xss.impl;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ExternalResourceChangeListener;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.apache.sling.serviceusermapping.ServiceUserMapped;
import org.apache.sling.xss.ProtectionContext;
import org.apache.sling.xss.XSSFilter;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.owasp.validator.html.model.Attribute;
import org.owasp.validator.html.model.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements the <code>XSSFilter</code> using the Antisamy XSS protection library found at
 * <a href="http://code.google.com/p/owaspantisamy/">http://code.google.com/p/owaspantisamy/</a>.
 */
@Component(
        service = {XSSFilter.class}
)
@Designate(ocd = XSSFilterImpl.Configuration.class)
public class XSSFilterImpl implements XSSFilter {

    @ObjectClassDefinition(
            name = "Apache Sling XSS Filter",
            description = "XSS filtering utility based on AntiSamy."
    )
    @interface Configuration {

        @AttributeDefinition(
                name = "AntiSamy Policy Path",
                description = "The path to the AntiSamy policy file (absolute or relative to the configured search paths)."

        )
        String policyPath() default XSSFilterImpl.DEFAULT_POLICY_PATH;

    }

    private final Logger logger = LoggerFactory.getLogger(XSSFilterImpl.class);

    public static final String ALPHA = "(?:\\p{L}\\p{M}*)";
    public static final String HEX_DIGIT = "\\p{XDigit}";
    public static final String PCT_ENCODED = "%" + HEX_DIGIT + HEX_DIGIT;
    public static final String UNRESERVED_CHARACTERS = ALPHA + "|[\\p{N}-._~]";
    public static final String SUB_DELIMS = "[!$&'()*+,;=]";
    public static final String REG_NAME = "(?:(?:" + UNRESERVED_CHARACTERS + ")*|(?:" + PCT_ENCODED + ")*|" + "(?:" + SUB_DELIMS + ")*)";
    public static final String PCHAR = UNRESERVED_CHARACTERS + "|" + PCT_ENCODED + "|" + SUB_DELIMS + "|:|@";
    public static final String DEC_OCTET = "(?:\\p{N}|[\\x31-\\x39]\\p{N}|1\\p{N}{2}|2[\\x30-\\x34]\\p{N}|25[\\x30-\\x35])";
    public static final String H16 = HEX_DIGIT + "{1,4}";
    public static final String IPv4_ADDRESS = DEC_OCTET + "\\." + DEC_OCTET + "\\." + DEC_OCTET + "\\." + DEC_OCTET;
    public static final String LS32 = "(?:" + H16 + ":" + H16 + ")|" + IPv4_ADDRESS;
    public static final String IPv6_ADDRESS = "(?:(?:(?:" + H16 + ":){6}(?:" + LS32 + "))|" +
            "(?:::(?:" + H16 + ":){5}(?:" + LS32 + "))|" +
            "(?:(?:" + H16 + "){0,1}::(?:" + H16 + ":){4}(?:" + LS32 + "))|" +
            "(?:(?:(?:" + H16 + ":){0,1}" + H16 + ")?::(?:" + H16 + ":){3}(?:" + LS32 + "))|" +
            "(?:(?:(?:" + H16 + ":){0,2}" + H16 + ")?::(?:" + H16 + ":){2}(?:" + LS32 + "))|" +
            "(?:(?:(?:" + H16 + ":){0,3}" + H16 + ")?::(?:" + H16 + ":){1}(?:" + LS32 + "))|" +
            "(?:(?:(?:" + H16 + ":){0,4}" + H16 + ")?::(?:" + LS32 + "))|" +
            "(?:(?:(?:" + H16 + ":){0,5}" + H16 + ")?::(?:" + H16 + "))|" +
            "(?:(?:(?:" + H16 + ":){0,6}" + H16 + ")?::))";
    public static final String IP_LITERAL = "\\[" + IPv6_ADDRESS + "]";
    public static final String PORT = "\\p{Digit}+";
    public static final String HOST = "(?:" + IP_LITERAL + "|" + IPv4_ADDRESS + "|" + REG_NAME + ")";
    public static final String USER_INFO = "(?:(?:" + UNRESERVED_CHARACTERS + ")|(?:" + PCT_ENCODED + ")|(?:" + SUB_DELIMS + "))*";
    public static final String AUTHORITY = "(?:" + USER_INFO + "@)?" + HOST + "(?::" + PORT + ")?";
    public static final String SCHEME_PATTERN = "(?!\\s*javascript)\\p{L}[\\p{L}\\p{N}+.\\-]*";
    public static final String FRAGMENT = "(?:" + PCHAR + "|/|\\?)*";
    public static final String QUERY = "(?:" + PCHAR + "|/|\\?)*";
    public static final String SEGMENT_NZ = "(?:" + PCHAR + ")+";
    public static final String SEGMENT_NZ_NC = "(?:" + UNRESERVED_CHARACTERS + "|" + PCT_ENCODED + "|" + SUB_DELIMS + "|@)+";
    public static final String PATH_ABEMPTY = "(?:/|(/" + SEGMENT_NZ + "/?)*)";
    public static final String PATH_ABSOLUTE = "/(?:" + SEGMENT_NZ + PATH_ABEMPTY + ")?";
    public static final String PATH_NOSCHEME = SEGMENT_NZ_NC + "(?:/|(/" + SEGMENT_NZ + ")*)";
    public static final String PATH_ROOTLESS = SEGMENT_NZ + "(?:/|(/" + SEGMENT_NZ + ")*)";
    public static final String PATH_EMPTY = "(?:^$)";
    public static final String RELATIVE_PART = "(?:(?://" + AUTHORITY + PATH_ABEMPTY +  ")|" +
            "(?:" + PATH_ABSOLUTE + ")|" +
            "(?:" + PATH_ROOTLESS + "))";
    public static final String HIER_PART = "(?:(?://" + AUTHORITY + PATH_ABEMPTY + ")|" +
            "(?:" + PATH_ABSOLUTE + ")|" +
            "(?:" + PATH_NOSCHEME + ")|" +
            PATH_EMPTY + ")";

    public static final String RELATIVE_REF = "(?!\\s*javascript(?::|&colon;))" + RELATIVE_PART + "?(?:\\?" + QUERY + ")?(?:#" + FRAGMENT + ")?";
    public static final String URI = SCHEME_PATTERN + ":" + HIER_PART + "(?:\\?" + QUERY + ")?(?:#" + FRAGMENT + ")?";


    // Default href configuration copied from the config.xml supplied with AntiSamy
    static final Attribute DEFAULT_HREF_ATTRIBUTE = new Attribute(
            "href",
            Arrays.asList(
                    Pattern.compile(RELATIVE_REF),
                    Pattern.compile(URI)
            ),
            Collections.<String>emptyList(),
            "removeAttribute", ""
    );

    static final String DEFAULT_POLICY_PATH = "sling/xss/config.xml";
    private static final String EMBEDDED_POLICY_PATH = "SLING-INF/content/config.xml";
    private PolicyHandler policyHandler;
    private Attribute hrefAttribute;
    private String policyPath;
    private boolean policyLoadedFromFile;
    private ServiceRegistration<ResourceChangeListener> serviceRegistration;

    // available contexts
    private final XSSFilterRule htmlHtmlContext = new HtmlToHtmlContentContext();
    private final XSSFilterRule plainHtmlContext = new PlainTextToHtmlContentContext();

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Reference
    private ServiceUserMapped serviceUserMapped;

    @Override
    public boolean check(final ProtectionContext context, final String src) {
        final XSSFilterRule ctx = this.getFilterRule(context);
        return ctx.check(policyHandler, src);
    }

    @Override
    public String filter(final String src) {
        return this.filter(XSSFilter.DEFAULT_CONTEXT, src);
    }

    @Override
    public String filter(final ProtectionContext context, final String src) {
        final XSSFilterRule ctx = this.getFilterRule(context);
        return ctx.filter(policyHandler, src);
    }

    @Override
    public boolean isValidHref(String url) {
        if (StringUtils.isEmpty(url)) {
            return true;
        }
        try {
            String decodedURL = URLDecoder.decode(url, StandardCharsets.UTF_8.name());
            /*
                StringEscapeUtils is deprecated starting with version 3.6 of commons-lang3, however the indicated replacement comes from
                commons-text, which is not an OSGi bundle
             */
            String xmlDecodedURL = StringEscapeUtils.unescapeXml(decodedURL);
            if (xmlDecodedURL.equals(url) || xmlDecodedURL.equals(decodedURL)) {
                return runHrefValidation(url);
            }
            return runHrefValidation(xmlDecodedURL);
        } catch (Exception e) {
            logger.warn("Unable to decode url.", e);
            logger.debug("URL input: {}", url);
        }
        return false;
    }

    private boolean runHrefValidation(@Nonnull String url) {
        // Same logic as in org.owasp.validator.html.scan.MagicSAXFilter.startElement()
        boolean isValid = hrefAttribute.containsAllowedValue(url.toLowerCase());
        if (!isValid) {
            isValid = hrefAttribute.matchesAllowedExpression(url.toLowerCase());
        }
        return isValid;
    }

    @Activate
    @Modified
    protected void activate(ComponentContext componentContext, Configuration configuration) {
        // load default handler
        policyLoadedFromFile = false;
        policyPath = configuration.policyPath();
        updatePolicy();
        if (serviceRegistration != null) {
            serviceRegistration.unregister();
        }
        Dictionary<String, Object> rclProperties = new Hashtable<>();
        rclProperties.put(ResourceChangeListener.CHANGES, new String[]{"ADDED", "CHANGED", "REMOVED"});
        rclProperties.put(ResourceChangeListener.PATHS, policyPath);
        if (policyLoadedFromFile) {
            serviceRegistration = componentContext.getBundleContext()
                    .registerService(ResourceChangeListener.class, new PolicyChangeListener(), rclProperties);
            logger.info("Registered a resource change listener for file {}.", policyPath);
        }
    }

    @Deactivate
    protected void deactivate() {
        if (serviceRegistration != null) {
            serviceRegistration.unregister();;
        }
    }

    private synchronized void updatePolicy() {
        this.policyHandler = null;
        try (final ResourceResolver xssResourceResolver = resourceResolverFactory.getServiceResourceResolver(null)) {
            Resource policyResource = xssResourceResolver.getResource(policyPath);
            if (policyResource != null) {
                try (InputStream policyStream = policyResource.adaptTo(InputStream.class)) {
                    setPolicyHandler(new PolicyHandler(policyStream));
                    logger.info("Installed policy from {}.", policyResource.getPath());
                    policyLoadedFromFile = true;
                } catch (Exception e) {
                    Throwable[] suppressed = e.getSuppressed();
                    if (suppressed.length > 0) {
                        for (Throwable t : suppressed) {
                            logger.error("Unable to load policy from " + policyResource.getPath(), t);
                        }
                    }
                    logger.error("Unable to load policy from " + policyResource.getPath(), e);
                }
            }
        } catch (final LoginException e) {
            logger.error("Unable to load the default policy file.", e);
        }
        if (policyHandler == null) {
            // the content was not installed but the service is active; let's use the embedded file for the default handler
            logger.info("Could not find a policy file at the configured location {}. Attempting to use the default resource embedded in" +
                    " the bundle.", policyPath);
            try (InputStream policyStream = this.getClass().getClassLoader().getResourceAsStream(EMBEDDED_POLICY_PATH)) {
                setPolicyHandler(new PolicyHandler(policyStream));
                logger.info("Installed policy from the embedded {} file from the bundle.", EMBEDDED_POLICY_PATH);
            } catch (Exception e) {
                Throwable[] suppressed = e.getSuppressed();
                if (suppressed.length > 0) {
                    for (Throwable t : suppressed) {
                        logger.error("Unable to load policy from embedded policy file.", t);
                    }
                }
                logger.error("Unable to load policy from embedded policy file.", e);
            }
        }
        if (policyHandler == null) {
            throw new IllegalStateException("Cannot load a policy handler.");
        }
    }


    /**
     * Get the filter rule context.
     */
    private XSSFilterRule getFilterRule(final ProtectionContext context) {
        if (context == null) {
            throw new NullPointerException("context");
        }
        if (context == ProtectionContext.HTML_HTML_CONTENT) {
            return this.htmlHtmlContext;
        }
        return this.plainHtmlContext;
    }

    private void setPolicyHandler(PolicyHandler policyHandler) {
        Tag linkTag = policyHandler.getPolicy().getTagByLowercaseName("a");
        Attribute hrefAttribute = (linkTag != null) ? linkTag.getAttributeByName("href") : null;
        if (hrefAttribute == null) {
            // Fallback to default configuration
            hrefAttribute = DEFAULT_HREF_ATTRIBUTE;
        }

        this.policyHandler = policyHandler;
        this.hrefAttribute = hrefAttribute;
    }

    private class PolicyChangeListener implements ResourceChangeListener, ExternalResourceChangeListener {
        @Override
        public void onChange(@Nonnull List<ResourceChange> resourceChanges) {
            for (ResourceChange change : resourceChanges) {
                if (change.getPath().endsWith(policyPath)) {
                    logger.info("Detected policy file change ({}) at {}. Updating policy handler.", change.getType().name(), change.getPath());
                    updatePolicy();
                }
            }
        }
    }
}
