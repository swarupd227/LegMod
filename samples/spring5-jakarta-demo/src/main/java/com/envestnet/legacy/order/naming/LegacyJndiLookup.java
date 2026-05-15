package com.envestnet.legacy.order.naming;

import com.ibm.websphere.naming.WsnInitialContextFactory;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.util.Hashtable;

/**
 * IBM WebSphere-specific JNDI lookup. After uplift this must become a vendor-
 * neutral javax.naming.InitialContext lookup (or jakarta.naming after the
 * Jakarta rename), with the WsnInitialContextFactory dependency removed.
 */
public class LegacyJndiLookup {

    public static Object lookup(String name) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY,
            WsnInitialContextFactory.class.getName());
        env.put(Context.PROVIDER_URL, "iiop://was-host:2809");

        try {
            InitialContext ctx = new InitialContext(env);
            return ctx.lookup(name);
        } catch (NamingException e) {
            throw new RuntimeException("JNDI lookup failed for " + name, e);
        }
    }
}
