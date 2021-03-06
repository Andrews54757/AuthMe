package me.axieum.mcmod.authme.util;

import com.mojang.authlib.Agent;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilUserAuthentication;
import com.mojang.util.UUIDTypeAdapter;
import me.axieum.mcmod.authme.AuthMe;
import me.axieum.mcmod.authme.api.Status;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class SessionUtil
{
    private static Status lastStatus = Status.UNKNOWN;
    private static long lastStatusCheck;

    /**
     * Authentication Services.
     */
    private static final YggdrasilAuthenticationService yas;
    private static final YggdrasilUserAuthentication yua;
    private static final YggdrasilMinecraftSessionService ymss;

    static {
        yas = new YggdrasilAuthenticationService(Minecraft.getInstance().getProxy(), UUID.randomUUID().toString());
        yua = (YggdrasilUserAuthentication) yas.createUserAuthentication(Agent.MINECRAFT);
        ymss = (YggdrasilMinecraftSessionService) yas.createMinecraftSessionService();
    }

    /**
     * Returns the current session.
     *
     * @return current session instance
     */
    public static Session getSession()
    {
        return Minecraft.getInstance().getSession();
    }

    /**
     * Checks and returns a completable future for the current session status.
     * NB: This is an expensive task as it involves connecting to servers to
     * validate the stored tokens, and hence is executed on a separate thread.
     * The response is cached for ~1 minutes.
     *
     * @return completable future for the status (async)
     */
    public static CompletableFuture<Status> getStatus()
    {
        if (System.currentTimeMillis() - lastStatusCheck < 60000)
            return CompletableFuture.completedFuture(lastStatus);

        return CompletableFuture.supplyAsync(() -> {
            final Session session = getSession();
            GameProfile gp = session.getProfile();
            String token = session.getToken();
            String id = UUID.randomUUID().toString();

            try {
                ymss.joinServer(gp, token, id);
                if (ymss.hasJoinedServer(gp, id, null).isComplete()) {
                    AuthMe.LOGGER.info("Session validated.");
                    lastStatus = Status.VALID;
                } else {
                    AuthMe.LOGGER.info("Session invalidated.");
                    lastStatus = Status.INVALID;
                }
            } catch (AuthenticationException e) {
                AuthMe.LOGGER.warn("Unable to validate the session: {}", e.getMessage());
                lastStatus = Status.INVALID;
            }

            lastStatusCheck = System.currentTimeMillis();
            return lastStatus;
        });
    }

    /**
     * Attempts to login and set a new session for the current Minecraft instance.
     *
     * @param username Minecraft account username
     * @param password Minecraft account password
     * @return completable future for the new session
     */
    public static CompletableFuture<Session> login(String username, String password)
    {
        return CompletableFuture.supplyAsync(() -> {
            try {
                AuthMe.LOGGER.info("Logging into a new session with username '{}'", username);

                // Set credentials and login
                yua.setUsername(username);
                yua.setPassword(password);
                yua.logIn();

                // Fetch useful session data
                final String name = yua.getSelectedProfile().getName();
                final String uuid = UUIDTypeAdapter.fromUUID(yua.getSelectedProfile().getId());
                final String token = yua.getAuthenticatedToken();
                final String type = yua.getUserType().getName();

                // Logout after fetching what is needed
                yua.logOut();

                // Persist the new session to the Minecraft instance
                final Session session = new Session(name, uuid, token, type);
                session.setProperties(yua.getUserProperties());
                setSession(session);

                AuthMe.LOGGER.info("Session login successful.");
                return session;
            } catch (AuthenticationException | IllegalAccessException e) {
                AuthMe.LOGGER.error("Session login failed: {}", e.getMessage());
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Mocks a login, setting the desired username on the session.
     * NB: Useful for offline play.
     *
     * @param username desired username
     * @return new session if success, else old session
     */
    @Nonnull
    public static Session login(String username)
    {
        try {
            UUID uuid = UUID.nameUUIDFromBytes(("offline:" + username).getBytes());

            final Session session = new Session(username, uuid.toString(), "invalidtoken", Session.Type.LEGACY.name());
            setSession(session);

            AuthMe.LOGGER.info("Session login (offline) successful.");
            return session;
        } catch (IllegalAccessException e) {
            AuthMe.LOGGER.error("Session login (offline) failed: {}", e.getMessage());
            return SessionUtil.getSession();
        }
    }

    /**
     * Replaces the session on the Minecraft instance.
     *
     * @param session new session with updated properties
     */
    private static void setSession(Session session) throws IllegalAccessException
    {
        // NB: Minecraft#session is a final property - use reflection
        ObfuscationReflectionHelper.findField(Minecraft.class, "field_71449_j")
                                   .set(Minecraft.getInstance(), session);

        // Cached status is now stale
        lastStatus = Status.UNKNOWN;
        lastStatusCheck = 0;
    }
}
