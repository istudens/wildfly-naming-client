/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.naming.client;

import static java.security.AccessController.doPrivileged;
import static org.wildfly.naming.client.util.EnvironmentUtils.CONNECT_OPTIONS;
import static org.wildfly.naming.client.util.EnvironmentUtils.EJB_HOST_KEY;
import static org.wildfly.naming.client.util.EnvironmentUtils.EJB_PORT_KEY;
import static org.wildfly.naming.client.util.EnvironmentUtils.EJB_REMOTE_CONNECTIONS;
import static org.wildfly.naming.client.util.EnvironmentUtils.EJB_REMOTE_CONNECTION_PREFIX;
import static org.wildfly.naming.client.util.EnvironmentUtils.EJB_REMOTE_CONNECTION_PROVIDER_PREFIX;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import javax.naming.Binding;
import javax.naming.CompositeName;
import javax.naming.ConfigurationException;
import javax.naming.Context;
import javax.naming.InvalidNameException;
import javax.naming.Name;
import javax.naming.NameClassPair;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.spi.NamingManager;

import org.wildfly.common.Assert;
import org.wildfly.common.expression.Expression;
import org.wildfly.naming.client._private.Messages;
import org.wildfly.naming.client.util.FastHashtable;
import org.wildfly.naming.client.util.NamingUtils;
import org.xnio.Options;

/**
 * A root context which locates providers based on the {@link Context#PROVIDER_URL} environment property as well as any
 * URL scheme which appears as a part of the JNDI name in the first segment.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:tadamski@redhat.com">Tomasz Adamski</a>
 */
public final class WildFlyRootContext implements Context {
    static {
        Version.getVersion();
    }
    private static final NameParser NAME_PARSER = CompositeName::new;

    private final FastHashtable<String, Object> environment;

    private final Object loaderLock = new Object();
    private final ServiceLoader<NamingProviderFactory> namingProviderServiceLoader;
    private final ServiceLoader<NamingContextFactory> namingContextServiceLoader;

    /**
     * Construct a new instance, searching the thread context class loader for providers.  If no context class loader is
     * set when this constructor is called, the class loader of this class is used.
     *
     * @param environment the environment to use (not copied)
     */
    public WildFlyRootContext(final FastHashtable<String, Object> environment) {
        this(environment, secureGetContextClassLoader());
    }

    /**
     * Construct a new instance, searching the given class loader for providers.
     *
     * @param environment the environment to use (not copied)
     * @param classLoader the class loader to search for providers
     */
    public WildFlyRootContext(final FastHashtable<String, Object> environment, final ClassLoader classLoader) {
        this.environment = environment;
        namingProviderServiceLoader = ServiceLoader.load(NamingProviderFactory.class, classLoader);
        namingContextServiceLoader = ServiceLoader.load(NamingContextFactory.class, classLoader);
    }

    private WildFlyRootContext(final FastHashtable<String, Object> environment, final ServiceLoader<NamingProviderFactory> namingProviderServiceLoader, final ServiceLoader<NamingContextFactory> namingContextServiceLoader) {
        this.environment = environment;
        this.namingProviderServiceLoader = namingProviderServiceLoader;
        this.namingContextServiceLoader = namingContextServiceLoader;
    }

    private static ClassLoader secureGetContextClassLoader() {
        final ClassLoader contextClassLoader;
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            contextClassLoader = doPrivileged((PrivilegedAction<ClassLoader>) WildFlyRootContext::getContextClassLoader);
        } else {
            contextClassLoader = getContextClassLoader();
        }
        return contextClassLoader == null ? WildFlyRootContext.class.getClassLoader() : contextClassLoader;
    }

    private static ClassLoader getContextClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    @Override
    public Object lookup(final String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        if (reparsedName.isEmpty()) {
            return new WildFlyRootContext(environment.clone(), namingProviderServiceLoader, namingContextServiceLoader);
        }
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return result.context.lookup(name);
        } else {
            return result.context.lookup(reparsedName.getName());
        }
    }

    @Override
    public Object lookup(Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        if (reparsedName.isEmpty()) {
            return new WildFlyRootContext(environment.clone(), namingProviderServiceLoader, namingContextServiceLoader);
        }
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return result.context.lookup(name);
        } else {
            return result.context.lookup(reparsedName.getName());
        }
    }

    @Override
    public void bind(final String name, final Object obj) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.bind(name, obj);
        } else {
            result.context.bind(reparsedName.getName(), obj);
        }
    }

    @Override
    public void bind(final Name name, final Object obj) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.bind(name, obj);
        } else {
            result.context.bind(reparsedName.getName(), obj);
        }
    }

    @Override
    public void rebind(final String name, final Object obj) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.rebind(name, obj);
        } else {
            result.context.rebind(reparsedName.getName(), obj);
        }
    }

    @Override
    public void rebind(final Name name, final Object obj) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.rebind(name, obj);
        } else {
            result.context.rebind(reparsedName.getName(), obj);
        }
    }

    @Override
    public void unbind(final String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.unbind(name);
        } else {
            result.context.unbind(reparsedName.getName());
        }
    }

    @Override
    public void unbind(final Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.unbind(name);
        } else {
            result.context.unbind(reparsedName.getName());
        }
    }

    @Override
    public void rename(final String oldName, final String newName) throws NamingException {
        Assert.checkNotNullParam("oldName", oldName);
        Assert.checkNotNullParam("newName", newName);
        final ReparsedName oldReparsedName = reparse(getNameParser().parse(oldName));
        final ReparsedName newReparsedName = reparse(getNameParser().parse(newName));
        ContextResult result = getProviderContext(oldReparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.rename(oldName, newName);
        } else {
            result.context.rename(oldReparsedName.getName(), newReparsedName.getName());
        }
    }

    @Override
    public void rename(final Name oldName, final Name newName) throws NamingException {
        Assert.checkNotNullParam("oldName", oldName);
        Assert.checkNotNullParam("newName", newName);
        final ReparsedName oldReparsedName = reparse(oldName);
        final ReparsedName newReparsedName = reparse(newName);
        ContextResult result = getProviderContext(oldReparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.rename(oldName, newName);
        } else {
            result.context.rename(oldReparsedName.getName(), newReparsedName.getName());
        }
    }

    @Override
    public NamingEnumeration<NameClassPair> list(final String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.list(
                    name));
        } else {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.list(
                    reparsedName.getName()));
        }
    }

    @Override
    public NamingEnumeration<NameClassPair> list(final Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.list(
                    name));
        } else {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.list(
                    reparsedName.getName()));
        }
    }

    @Override
    public NamingEnumeration<Binding> listBindings(final String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.listBindings(
                    name));
        } else {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.listBindings(
                    reparsedName.getName()));
        }
    }

    @Override
    public NamingEnumeration<Binding> listBindings(final Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.listBindings(
                    name));
        } else {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.listBindings(
                    reparsedName.getName()));
        }
    }

    @Override
    public void destroySubcontext(final String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.destroySubcontext(name);
        } else {
            result.context.destroySubcontext(reparsedName.getName());
        }
    }

    @Override
    public void destroySubcontext(final Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.destroySubcontext(name);
        } else {
            result.context.destroySubcontext(reparsedName.getName());
        }
    }

    @Override
    public Context createSubcontext(final String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return result.context.createSubcontext(name);
        } else {
            return result.context.createSubcontext(reparsedName.getName());
        }
    }

    @Override
    public Context createSubcontext(final Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return result.context.createSubcontext(name);
        } else {
            return result.context.createSubcontext(reparsedName.getName());
        }
    }

    @Override
    public Object lookupLink(final String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return result.context.lookupLink(name);
        } else {
            return result.context.lookupLink(reparsedName.getName());
        }
    }

    @Override
    public Object lookupLink(final Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return result.context.lookupLink(name);
        } else {
            return result.context.lookupLink(reparsedName.getName());
        }
    }

    @Override
    public NameParser getNameParser(Name name) throws NamingException {
        return getNameParser();
    }

    @Override
    public NameParser getNameParser(String s) throws NamingException {
        return getNameParser();
    }

    private NameParser getNameParser(){
        return NAME_PARSER;
    }

    public String composeName(final String name, final String prefix) throws NamingException {
        Assert.checkNotNullParam("name", name);
        Assert.checkNotNullParam("prefix", prefix);
        return composeName(getNameParser().parse(name), getNameParser().parse(prefix)).toString();
    }

    public Name composeName(final Name name, final Name prefix) throws NamingException {
        return prefix.addAll(name);
    }

    public Object addToEnvironment(final String propName, final Object propVal) {
        return environment.put(propName, propVal);
    }

    public Object removeFromEnvironment(final String propName) {
        return environment.remove(propName);
    }

    @Override
    public FastHashtable<String, Object> getEnvironment() throws NamingException {
        return environment;
    }

    public void close() throws NamingException {
    }

    public String getNameInNamespace() throws NamingException {
        // always root
        return "";
    }

    private ContextResult getProviderContext(final String nameScheme) throws NamingException {
        // get provider scheme
        final String urlString = getProviderUrl(getEnvironment());
        final List<URI> providerUris = getProviderUris(urlString);
        if (providerUris == null || providerUris.stream().allMatch(uri -> (uri.getScheme() == null || uri.getScheme().isEmpty()))) {
            // search for context factories which support a null provider
            synchronized (loaderLock) {
                final Iterator<NamingContextFactory> contextIterator = namingContextServiceLoader.iterator();
                for (;;) try {
                    if (! contextIterator.hasNext()) break;
                    final NamingContextFactory contextFactory = contextIterator.next();
                    if (contextFactory.supportsUriScheme(null, nameScheme)) {
                        return new ContextResult(contextFactory.createRootContext(null, nameScheme, getEnvironment()), false);
                    }
                } catch (ServiceConfigurationError error) {
                    Messages.log.serviceConfigFailed(error);
                }
            }
            if (nameScheme != null) {
                // there is a name scheme to resolve; try the old-fashioned way
                final Context context = NamingManager.getURLContext(nameScheme, environment);
                if (context != null) {
                    return new ContextResult(context, true);
                }
            }
            // by default, support an empty local root context
            return  new ContextResult(NamingUtils.emptyContext(getEnvironment()), false);
        }
        // get active naming providers
        final ServiceLoader<NamingProviderFactory> providerLoader = this.namingProviderServiceLoader;
        synchronized (providerLoader) {
            final Iterator<NamingProviderFactory> providerIterator = providerLoader.iterator();
            for (;;) try {
                if (! providerIterator.hasNext()) break;
                final NamingProviderFactory providerFactory = providerIterator.next();
                boolean supportsUriSchemes = true;
                for (URI providerUri : providerUris) {
                    if (! providerFactory.supportsUriScheme(providerUri.getScheme(), getEnvironment())) {
                        supportsUriSchemes = false;
                        break;
                    }
                }
                if (supportsUriSchemes) {
                    final NamingProvider provider = providerFactory.createProvider(getEnvironment(), providerUris.toArray(new URI[providerUris.size()]));
                    final Iterator<NamingContextFactory> contextIterator = namingContextServiceLoader.iterator();
                    for (;;) try {
                        if (! contextIterator.hasNext()) break;
                        final NamingContextFactory contextFactory = contextIterator.next();
                        if (contextFactory.supportsUriScheme(provider, nameScheme)) {
                            return  new ContextResult(contextFactory.createRootContext(provider, nameScheme, getEnvironment()), false);
                        }
                    } catch (ServiceConfigurationError error) {
                        Messages.log.serviceConfigFailed(error);
                    }
                }
            } catch (ServiceConfigurationError error) {
                Messages.log.serviceConfigFailed(error);
            }
            if (nameScheme != null) {
                // there is a name scheme to resolve; try the old-fashioned way
                final Context context = NamingManager.getURLContext(nameScheme, environment);
                if (context != null) {
                    return  new ContextResult(context, true);
                }
            }
            throw Messages.log.noProviderForUri(nameScheme);
        }
    }

    /**
     * Get the provider URL. If a provider URL has not been specified but properties for an EJB remote connection have
     * been specified, attempt to determine the provider URL from the EJB remote connection host and port properties.
     *
     * @param env the environment (must not be {@code null})
     * @return the provider URL, or {@code null} if there is none or if it cannot be determined from other properties
     */
    private String getProviderUrl(final FastHashtable<String, Object> env) {
        Object urlString = env.get(Context.PROVIDER_URL);
        if (urlString != null) {
            return Expression.compile(urlString.toString(), Expression.Flag.LENIENT_SYNTAX).evaluateWithPropertiesAndEnvironment(false);
        }
        final String connectionName = (String) env.getOrDefault(EJB_REMOTE_CONNECTIONS, "");
        if (connectionName.isEmpty() || connectionName.contains(",")) {
            // either no EJB connection properties were specified or multiple EJB connections were specified,
            // cannot directly convert to equivalent naming properties
            return null;
        }
        // only one EJB connection was specified, attempt to determine the PROVIDER_URL from the EJB HOST and PORT properties
        final String ejbPrefix = EJB_REMOTE_CONNECTION_PREFIX + connectionName + ".";
        final String host = getStringProperty(ejbPrefix + EJB_HOST_KEY, env);
        final String port = getStringProperty(ejbPrefix + EJB_PORT_KEY, env);
        String sslEnabled = getStringProperty(ejbPrefix + CONNECT_OPTIONS + Options.SSL_ENABLED, env);
        if (sslEnabled == null) {
            sslEnabled = getStringProperty(EJB_REMOTE_CONNECTION_PROVIDER_PREFIX + Options.SSL_ENABLED, env);
        }
        final String protocol;
        if (Boolean.parseBoolean(sslEnabled)) {
            protocol = "remote+https";
        } else {
            protocol = "remote+http";
        }
        if (host != null && port != null) {
            String realHost = Expression.compile(host, Expression.Flag.LENIENT_SYNTAX).evaluateWithPropertiesAndEnvironment(false);
            if (realHost.indexOf(':') != -1 && ! realHost.startsWith("[") && ! realHost.endsWith("]")) {
                // probably IPv6?
                realHost = "[" + realHost + "]";
            }
            return protocol + "://" + realHost + ":" + port;
        }
        return null;
    }

    private String getStringProperty(final String propertyName, final FastHashtable<String, Object> env) {
        final Object propertyValue = env.get(propertyName);
        return propertyValue == null ? null : (String) propertyValue;
    }

    private List<URI> getProviderUris(final String providerUrl) throws ConfigurationException {
        if (providerUrl == null || providerUrl.isEmpty()) {
            return null;
        }
        final String[] urls = providerUrl.split(",");
        final List<URI> providerUris = new ArrayList<>(urls.length);
        for (String url : urls) {
            URI providerUri;
            try {
                providerUri = new URI(url.trim());
            } catch (URISyntaxException e) {
                throw Messages.log.invalidProviderUri(e, url);
            }
            providerUris.add(providerUri);
        }
        return providerUris;
    }

    ReparsedName reparse(final Name origName) throws InvalidNameException {
        final Name name = (Name) origName.clone();
        if (name.isEmpty()) {
            return new ReparsedName(null, name);
        }
        final String first = name.get(0);
        final int idx = first.indexOf(':');
        final String urlScheme;
        if (idx != -1) {
            urlScheme = first.substring(0, idx);
            final String segment = first.substring(idx+1);

            name.remove(0);
            if(segment.length()>0 || (origName.size()>1 && origName.get(1).length()>0)){
                name.add(0, segment);
            }
            return new ReparsedName(urlScheme.isEmpty() ? null : urlScheme, name);
        } else {
            return new ReparsedName(null, name);
        }
    }

    class ReparsedName {
        final String urlScheme;
        final Name name;

        ReparsedName(final String urlScheme, final Name name){
            this.urlScheme = urlScheme;
            this.name = name;
        }

        public String getUrlScheme() {
            return urlScheme;
        }

        public Name getName() {
            return name;
        }

        boolean isEmpty(){
            return urlScheme == null && name.isEmpty();
        }
    }

    private class ContextResult {
        final Context context;
        final boolean oldStyle;

        private ContextResult(Context context, boolean oldStyle) {
            this.context = context;
            this.oldStyle = oldStyle;
        }


    }
}
