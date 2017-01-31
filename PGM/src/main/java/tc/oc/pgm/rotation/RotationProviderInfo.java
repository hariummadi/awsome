package tc.oc.pgm.rotation;

import javax.annotation.Nonnull;

import com.google.common.base.Preconditions;

public class RotationProviderInfo implements Comparable<RotationProviderInfo> {
    public final @Nonnull
    RotationProvider provider;
    public final int priority;
    public final @Nonnull String name;

    public RotationProviderInfo(@Nonnull RotationProvider provider, @Nonnull String name, int priority) {
        Preconditions.checkNotNull(provider, "rotation provider");
        Preconditions.checkNotNull(name, "name");

        this.provider = provider;
        this.name = name;
        this.priority = priority;
    }

    @Override
    public int compareTo(RotationProviderInfo o) {
        int c = -Integer.compare(this.priority, o.priority);
        if(c == 0) {
            c = this.name.compareTo(o.name);
        }
        return c;
    }
}
