package maxsuperman.addons.roller.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class VillagerRoller extends Module {
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgNotify = settings.createGroup("Notifications");

    private final Setting<Boolean> inGameEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("in-game-enabled")
        .description("Enable or disable the mod logic while the module is on")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enableKeybind = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-keybind-toggle")
        .description("Allow toggling in-game enable with a keybind")
        .defaultValue(true)
        .build()
    );

    private final Setting<Keybind> toggleKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("toggle-key")
        .description("Keybind to toggle in-game enable")
        .defaultValue(Keybind.none())
        .visible(enableKeybind::get)
        .build()
    );

    private final Setting<Integer> totemThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("totem-threshold")
        .description("Trigger notification when totem count reaches this number or below")
        .defaultValue(1)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<String> webhookUrl = sgNotify.add(new StringSetting.Builder()
        .name("webhook-url")
        .description("Webhook endpoint to call when a notification triggers")
        .defaultValue("http://127.0.0.1:8080/pull")
        .build()
    );

    private final Setting<Boolean> notifyOnDropOnly = sgNotify.add(new BoolSetting.Builder()
        .name("notify-on-drop-only")
        .description("Only notify when totem count drops to 1 from above")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> chatNotify = sgNotify.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Send a chat warning when notification triggers")
        .defaultValue(true)
        .build()
    );

    private int lastTotemCount = -1;

    public VillagerRoller() {
        super(Categories.Misc, "auto-use-totem", "Notifies when you are down to one totem.");
    }

    @Override
    public void onActivate() {
        lastTotemCount = -1;
    }

    @Override
    public String getInfoString() {
        return inGameEnabled.get() ? "Enabled" : "Disabled";
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ClientPlayerEntity player = mc.player;
        if (player == null) return;

        if (enableKeybind.get() && toggleKey.get().isPressed()) {
            boolean next = !inGameEnabled.get();
            inGameEnabled.set(next);
            player.sendMessage(Text.literal("[Auto Totem] " + (next ? "Enabled" : "Disabled")), true);
        }

        if (!inGameEnabled.get()) return;

        int currentTotemCount = countTotemsInInventory(player.getInventory());
        int threshold = totemThreshold.get();
        boolean shouldNotify = false;
        if (currentTotemCount <= threshold) {
            if (notifyOnDropOnly.get()) {
                shouldNotify = lastTotemCount > threshold;
            } else {
                shouldNotify = true;
            }
        }

        if (shouldNotify) {
            if (chatNotify.get()) {
                String message = threshold == 1
                    ? "[Auto Totem] Warning: Only one totem left."
                    : String.format("[Auto Totem] Warning: Only %d totems left.", currentTotemCount);
                player.sendMessage(Text.literal(message), true);
            }
            // Only send webhook once when count drops below threshold
            if (lastTotemCount > threshold) {
                String url = webhookUrl.get().trim();
                if (!url.isEmpty()) {
                    sendWebhookNotification(url);
                }
            }
        }

        lastTotemCount = currentTotemCount;
    }

    private static int countTotemsInInventory(PlayerInventory inventory) {
        int count = 0;
        for (ItemStack stack : inventory.main) {
            if (stack.isOf(Items.TOTEM_OF_UNDYING)) count += stack.getCount();
        }
        for (ItemStack stack : inventory.offHand) {
            if (stack.isOf(Items.TOTEM_OF_UNDYING)) count += stack.getCount();
        }
        return count;
    }

    private void sendWebhookNotification(String url) {
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(url))
                    .GET()
                    .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    warning("Webhook returned status code: %d", response.statusCode());
                }
            } catch (Exception e) {
                error("Failed to send webhook notification", e);
            }
        });
    }
}
