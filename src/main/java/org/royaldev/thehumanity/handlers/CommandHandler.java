package org.royaldev.thehumanity.handlers;

import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.royaldev.thehumanity.commands.Command;
import org.royaldev.thehumanity.commands.IRCCommand;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * A class for registering and retrieving {@link org.royaldev.thehumanity.commands.IRCCommand}s.
 */
public class CommandHandler implements Handler<IRCCommand, String> {

    private final Map<String, IRCCommand> commands = new TreeMap<>();
    // Alias, Command
    private final Map<String, String> aliasCommands = new TreeMap<>();

    private void checkCommand(@NotNull final IRCCommand command) {
        Validate.notNull(command, "command was null");
        final Command c = command.getCommandAnnotation();
        if (c == null) {
            throw new IllegalArgumentException("No Command annotation on IRCCommand.");
        }
    }

    /**
     * Gets a command for the command name. Case does not matter.
     *
     * @param name Name of the command to get
     * @return IRCCommand, or null if none registered
     */
    @Nullable
    public IRCCommand get(@NotNull String name) {
        Validate.notNull(name, "name was null");
        name = name.toLowerCase();
        synchronized (this.commands) {
            if (this.commands.containsKey(name)) return this.commands.get(name);
            synchronized (this.aliasCommands) {
                if (this.aliasCommands.containsKey(name)) return this.get(this.aliasCommands.get(name));
            }
        }
        return null;
    }

    /**
     * Gets all commands registered.
     *
     * @return Collection
     */
    @NotNull
    public Collection<IRCCommand> getAll() {
        synchronized (this.commands) {
            return this.commands.values();
        }
    }

    /**
     * Registers a command into the CommandHandler.
     * <br/>
     * <strong>Note:</strong> If a command with the same name is already registered, this method will <em>not</em>
     * register your command.
     *
     * @param command Command to be registered
     * @return If command was registered
     */
    @Override
    public boolean register(@NotNull final IRCCommand command) {
        Validate.notNull(command, "command was null");
        this.checkCommand(command);
        final String name = command.getName().toLowerCase();
        synchronized (this.commands) {
            this.commands.putIfAbsent(name, command);
        }
        Arrays.asList(command.getAliases()).stream().map(String::toLowerCase).forEach(alias -> {
            synchronized (this.aliasCommands) {
                this.aliasCommands.putIfAbsent(alias, name);
            }
        });
        return true;
    }

    /**
     * Removes the supplied command from the CommandHandler.
     *
     * @param command Command to be removed
     * @return If command was removed
     */
    @Override
    public boolean unregister(@NotNull final IRCCommand command) {
        Validate.notNull(command, "command was null");
        return this.unregister(command.getName());
    }

    /**
     * Removes a registered command by its name. Case does not matter.
     * <br/>
     * If no command is registered under the provided name, this method does nothing.
     *
     * @param name Name to remove
     * @return If command was unregistered
     */
    public boolean unregister(@NotNull String name) {
        Validate.notNull(name, "name was null");
        name = name.toLowerCase();
        boolean wasRemoved = false;
        synchronized (this.commands) {
            if (this.commands.containsKey(name)) {
                this.commands.remove(name);
                wasRemoved = true;
            }
        }
        synchronized (this.aliasCommands) {
            if (this.aliasCommands.containsKey(name)) this.aliasCommands.remove(name);
        }
        return wasRemoved;
    }
}
