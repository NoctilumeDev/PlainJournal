package com.ecommerce.catalog.infrastructure.datasource;

import java.util.ArrayDeque;
import java.util.Deque;

final class CatalogReadRouteContext {

    private static final ThreadLocal<Deque<Preference>> PREFERENCES =
            ThreadLocal.withInitial(ArrayDeque::new);

    private CatalogReadRouteContext() {
    }

    static Scope preferReplica() {
        return push(Preference.REPLICA);
    }

    static Scope forcePrimary() {
        return push(Preference.PRIMARY);
    }

    static boolean shouldUseReplica() {
        Deque<Preference> preferences = PREFERENCES.get();
        if (preferences.contains(Preference.PRIMARY)) {
            return false;
        }
        return preferences.contains(Preference.REPLICA);
    }

    private static Scope push(Preference preference) {
        PREFERENCES.get().push(preference);
        return new Scope(preference);
    }

    enum Preference {
        PRIMARY,
        REPLICA
    }

    static final class Scope implements AutoCloseable {

        private final Preference preference;
        private boolean closed;

        private Scope(Preference preference) {
            this.preference = preference;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Deque<Preference> preferences = PREFERENCES.get();
            Preference current = preferences.poll();
            if (current != preference) {
                preferences.clear();
                PREFERENCES.remove();
                throw new IllegalStateException("Catalog read route scopes closed out of order");
            }
            if (preferences.isEmpty()) {
                PREFERENCES.remove();
            }
        }
    }
}
