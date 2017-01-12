package be.maximvdw.spigotas;

import be.maximvdw.spigotas.config.Configuration;
import be.maximvdw.spigotas.storage.LogStorage;
import be.maximvdw.spigotas.ui.Console;
import be.maximvdw.spigotsite.SpigotSiteCore;
import be.maximvdw.spigotsite.api.SpigotSite;
import be.maximvdw.spigotsite.api.exceptions.ConnectionFailedException;
import be.maximvdw.spigotsite.api.exceptions.SpamWarningException;
import be.maximvdw.spigotsite.api.resource.Buyer;
import be.maximvdw.spigotsite.api.resource.PremiumResource;
import be.maximvdw.spigotsite.api.resource.Resource;
import be.maximvdw.spigotsite.api.user.Conversation;
import be.maximvdw.spigotsite.api.user.User;
import be.maximvdw.spigotsite.api.user.exceptions.InvalidCredentialsException;
import be.maximvdw.spigotsite.api.user.exceptions.TwoFactorAuthenticationException;
import com.github.silk8192.jpushbullet.PushbulletClient;
import com.github.silk8192.jpushbullet.items.push.Push;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Spigot Buyer Notification
 *
 * @author Maxim Van de Wynckel
 */
public class SpigotBuyerNotification {
    /* Authenticated user */
    private User user = null;
    private HashMap<Resource, List<Buyer>> buyers = new HashMap<>();
    private boolean running = true;
    private LogStorage log = null;
    private String accessToken = "";

    @SuppressWarnings("deprecation")
    public SpigotBuyerNotification(String... args) {
        Console.info("Initializing Spigot Buyer Notification v1.2.0 ...");
        new SpigotSiteCore();
        new Configuration(2); // Version 2

        String username = Configuration.getString("username");
        String password = Configuration.getString("password");
        String totpSecret = Configuration.getString("2fakey");

        accessToken = Configuration.getString("access-token");
        final int interval = Configuration.getInt("interval");

        Console.info("Logging in " + username + " ...");
        try {
            if (totpSecret.equals("")) {
                User user = SpigotSite.getAPI().getUserManager()
                        .authenticate(username, password);
                setUser(user);
            } else {
                User user = SpigotSite.getAPI().getUserManager()
                        .authenticate(username, password, totpSecret);
                setUser(user);
            }
        } catch (InvalidCredentialsException e) {
            Console.info("Unable to log in! Wrong credentials!");
            return;
        } catch (TwoFactorAuthenticationException e) {
            Console.info("Unable to log in! Two factor authentication failed!");
            return;
        } catch (ConnectionFailedException e) {
            Console.info("Unable to connect to spigot");
            return;
        }
        password = null;
        totpSecret = null;

        Console.info("Getting your premium plugins ...");
        if (user == null)
            return;
        try {
            List<Resource> resources = SpigotSite.getAPI().getResourceManager()
                    .getResourcesByUser(user);
            for (Resource res : resources) {
                if (res instanceof PremiumResource) {
                    Console.info("\t" + res.getResourceName());
                    List<Buyer> resourceBuyers = SpigotSite
                            .getAPI()
                            .getResourceManager()
                            .getPremiumResourceBuyers(
                                    (PremiumResource) res, user);
                    buyers.put(
                            res,
                            resourceBuyers);
                    Console.info("\t\tBuyers: " + resourceBuyers.size());
                }
            }
        } catch (ConnectionFailedException ex) {
            ex.printStackTrace();
            return;
        }

        if (buyers.isEmpty()) {
            Console.info("You do not have any premium plugins!");
            return;
        }

        log = new LogStorage("log");
        logMessage("Starting now ...");

        Thread th = new Thread(new Runnable() {

            public void run() {
                while (running) {
                    try {
                        Thread.sleep(interval * 1000);
                        for (Resource res : buyers.keySet()) {
                            Console.info("Checking for new buyers in: "
                                    + res.getResourceName());
                            List<Buyer> newBuyers = SpigotSite
                                    .getAPI()
                                    .getResourceManager()
                                    .getPremiumResourceBuyers(
                                            (PremiumResource) res, user);
                            if (newBuyers.isEmpty() && (!buyers.isEmpty()))
                                continue;
                            for (Buyer newBuyer : newBuyers) {
                                if (!buyers.get(res).contains(newBuyer)) {
                                    if (newBuyer.addedByAuthor()){
                                        String title = Configuration
                                                .getString("title-added")
                                                .replace("{plugin}",
                                                        res.getResourceName())
                                                .replace("{member}",
                                                        newBuyer.getUsername());
                                        String message = Configuration
                                                .getString("message-added")
                                                .replace("{plugin}",
                                                        res.getResourceName())
                                                .replace("{member}",
                                                        newBuyer.getUsername());

                                        PushbulletClient client = new PushbulletClient(accessToken);
                                        Console.info("\tNew buyer added: " + newBuyer.getUsername());
                                        Push result = client.sendNotePush(title, message);

                                        logMessage("[BUYER] "
                                                + newBuyer.getUsername() + " ["
                                                + newBuyer.getUserId()
                                                + "] has been manually added to "
                                                + res.getResourceName());
                                    }else {
                                        String title = Configuration
                                                .getString("title")
                                                .replace("{plugin}",
                                                        res.getResourceName())
                                                .replace("{member}",
                                                        newBuyer.getUsername())
                                                .replace("{currency}",
                                                        newBuyer.getPurchaseCurrency())
                                                .replace("{price}",
                                                        String.valueOf(newBuyer.getPurchasePrice()));
                                        String message = Configuration
                                                .getString("message")
                                                .replace("{plugin}",
                                                        res.getResourceName())
                                                .replace("{member}",
                                                        newBuyer.getUsername())
                                                .replace("{currency}",
                                                        newBuyer.getPurchaseCurrency())
                                                .replace("{price}",
                                                        String.valueOf(newBuyer.getPurchasePrice()));

                                        PushbulletClient client = new PushbulletClient(accessToken);
                                        Console.info("\tNew buyer: " + newBuyer.getUsername());
                                        Push result = client.sendNotePush(title, message);

                                        logMessage("[BUYER] "
                                                + newBuyer.getUsername() + " ["
                                                + newBuyer.getUserId()
                                                + "] bought "
                                                + res.getResourceName());
                                    }
                                }

                            }

                            buyers.put(res, newBuyers);
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }

        });
        th.start();
        Console.info("Press ENTER to quit.");
        System.console().readLine();
        th.stop();
        running = false;
    }

    private void logMessage(String message) {
        Date today;
        String output;
        SimpleDateFormat formatter;

        formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH);
        today = new Date();
        output = formatter.format(today);
        String resultMessage = "[" + output + "] " + message;
        log.appendLog(resultMessage);
    }

    public static void main(String... args) {
        new SpigotBuyerNotification(args);
    }

    public User getUser() {
        return user;
    }

    private void setUser(User user) {
        this.user = user;
    }
}
