package org.royaldev.thehumanity.ping;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.element.Channel;
import org.kitteh.irc.client.library.element.User;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class PingRegistry implements Serializable {

    private static final long serialVersionUID = 1337L;

    private final List<PingRegistration> registrations = Lists.newArrayList();

    public static PingRegistry deserializeOrMakePingRegistry() {
        final File registryFile = new File("pingregistry.dat");
        if (!registryFile.exists()) return new PingRegistry();
        try (final ObjectInputStream ois = new ObjectInputStream(new GZIPInputStream(new FileInputStream(registryFile)))) {
            return (PingRegistry) ois.readObject();
        } catch (final IOException | ClassNotFoundException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Adds a registration to the list. If the account already has a registration, an exception will be thrown.
     *
     * @param account Account to add registration for
     * @return The added registration, suitable for modification
     * @throws IllegalStateException If the account already has a registration
     */
    public PingRegistration addRegistration(@NotNull final String account) {
        Preconditions.checkNotNull(account, "account was null");
        if (this.hasRegistration(account)) {
            throw new IllegalStateException(account + " already has a registration.");
        }
        final PingRegistration registration = new PingRegistration(account);
        // Probably want to receive notifications by default
        registration.addOption(PingRegistrationOption.RECEIVE);
        // Add the option to ping all authed nicks by default. Only those who have more than one authed nick will need
        // to turn this off.
        registration.addOption(PingRegistrationOption.PING_ALL_AUTHED_NICKS);
        this.registrations.add(registration);
        return registration;
    }

    /**
     * Gets the registration for the given account name. If there is no registration for the account, this will return
     * null.
     *
     * @param account Account to get registration for
     * @return PingRegistration or null
     */
    @Nullable
    public PingRegistration getRegistration(@NotNull final String account) {
        Preconditions.checkNotNull(account, "account was null");
        return this.getRegistrations().stream()
            .filter(reg -> reg.getServicesAccount().equals(account))
            .findFirst().orElse(null);
    }

    /**
     * Gets an unmodifiable list of registrations.
     *
     * @return Unmodifiable list
     */
    @NotNull
    public List<PingRegistration> getRegistrations() {
        return Collections.unmodifiableList(this.registrations);
    }

    /**
     * Checks to see if the account already has a registration stored.
     *
     * @param account Account to check
     * @return true if the account has a registration stored
     */
    public boolean hasRegistration(@NotNull final String account) {
        return this.getRegistration(account) != null;
    }

    /**
     * Removes a registration for this account. If the account has no registration, an exception will be thrown.
     *
     * @param account Account to remove registration for
     * @return Result of the {@link List#remove(Object)} method
     * @throws IllegalStateException If the account has no registration
     */
    public boolean removeRegistration(@NotNull final String account) {
        Preconditions.checkNotNull(account, "account was null");
        final PingRegistration registration = this.getRegistration(account);
        if (registration == null) {
            throw new IllegalStateException(account + " has no registration.");
        }
        return this.registrations.remove(registration);
    }

    /**
     * Saves the registry.
     */
    public void save() {
        final File registryFile = new File("pingregistry.dat");
        if (!registryFile.exists()) {
            try {
                if (!registryFile.createNewFile()) {
                    throw new RuntimeException(new IOException("Could not make ping registry file."));
                }
            } catch (final IOException ex) {
                throw new RuntimeException(ex);
            }
        }
        try (final ObjectOutputStream oos = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(registryFile)))) {
            oos.writeObject(this);
            oos.flush();
        } catch (final IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void sendNotification(@NotNull final User requester, @NotNull final Channel channel, @NotNull final Client client, @NotNull final String nickname) {
        Preconditions.checkNotNull(requester, "requester was null");
        Preconditions.checkNotNull(channel, "channel was null");
        Preconditions.checkNotNull(client, "client was null");
        Preconditions.checkNotNull(nickname, "nickname was null");
        client.sendMessage(nickname, "Your presence has been requested by " + requester.getMessagingName() + " for a game of Cards Against Humanity in " + channel.getMessagingName() + ".");
    }

    public void sendNotifications(@NotNull final User requester, @NotNull final Channel channel, @NotNull final Client client, @NotNull final WhoX whoX) {
        Preconditions.checkNotNull(requester, "requester was null");
        Preconditions.checkNotNull(channel, "channel was null");
        Preconditions.checkNotNull(client, "client was null");
        Preconditions.checkNotNull(whoX, "whoX was null");
        final List<String> accountsInChannel = channel.getNicknames().stream()
            .map(whoX::getAccount)
            .filter(account -> account != null)
            .collect(Collectors.toList());
        this.getRegistrations().stream()
            .filter(r -> accountsInChannel.contains(r.getServicesAccount()))
            .filter(r -> r.hasOption(PingRegistrationOption.RECEIVE))
            .filter(r -> !r.getServicesAccount().equalsIgnoreCase(whoX.getAccount(requester.getMessagingName())))
            .forEach(r -> {
                final Collection<String> nicks = whoX.getNicks(r.getServicesAccount());
                if (r.hasOption(PingRegistrationOption.PING_ALL_AUTHED_NICKS)) {
                    nicks.forEach(nick -> this.sendNotification(requester, channel, client, nick));
                } else {
                    nicks.stream().findFirst().ifPresent(nick -> this.sendNotification(requester, channel, client, nick));
                }
            });
    }
}
