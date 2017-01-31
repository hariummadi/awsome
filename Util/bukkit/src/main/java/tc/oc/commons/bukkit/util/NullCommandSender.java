package tc.oc.commons.bukkit.util;

import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;

/**
 * A {@link CommandSender} with no permissions that discards any messages sent to it.
 */
public class NullCommandSender extends NullPermissible implements CommandSender {

    public static final NullCommandSender INSTANCE = new NullCommandSender();

    @Override
    public void sendMessage(String s) {
    }

    @Override
    public void sendMessage(String[] strings) {
    }

    @Override
    public void sendMessage(BaseComponent... baseComponents) {
    }

    @Override
    public void sendMessage(BaseComponent baseComponent) {
    }

    @Override
    public Server getServer() {
        return Bukkit.getServer();
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public String getName(CommandSender sender) {
        return getName();
    }
}
