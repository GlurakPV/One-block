package de.oneblock;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * Inselauswahl als echtes Truhen-Menue (ersetzt die Marker-Truhe des Datapacks).
 * Es ist ein reines Server-Menue: Spieler brauchen keine Mod auf dem Client.
 */
final class IslandMenu extends ChestMenu {
    private static final Item[] DYES = {
            Items.LIME_DYE, Items.WHITE_DYE, Items.RED_DYE, Items.LIGHT_BLUE_DYE, Items.YELLOW_DYE, Items.ORANGE_DYE,
            Items.PURPLE_DYE, Items.CYAN_DYE, Items.PINK_DYE, Items.BROWN_DYE, Items.MAGENTA_DYE, Items.GREEN_DYE};

    private IslandMenu(int id, Inventory inv, SimpleContainer c) {
        super(MenuType.GENERIC_9x2, id, inv, c, 2);
    }

    static void open(ServerPlayer p) {
        Game g = Game.I;
        SimpleContainer c = new SimpleContainer(18);
        for (int i = 0; i < Game.MAX_ISLANDS; i++) {
            Data.IslandInfo info = g.data.islands.get(i);
            int rgb = Integer.parseInt(info.color.substring(1), 16);
            ItemStack stack = new ItemStack(DYES[i]);
            Component name = Component.literal("Insel " + (i + 1) + " (" + info.name + ")")
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withItalic(false));
            UUID o = g.ownerOf(i + 1);
            Component suffix;
            if (o != null) {
                ServerPlayer op = g.player(o);
                suffix = Component.literal(" - vergeben an " + (op != null ? op.getName().getString() : "(offline)"))
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false));
            } else if (g.isl[i].status == Game.NONE) {
                suffix = Component.literal(" - noch nicht generiert")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withItalic(false));
            } else {
                suffix = Component.literal(" - frei")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withItalic(false));
            }
            stack.set(DataComponents.CUSTOM_NAME, name.copy().append(suffix));
            c.setItem(i, stack);
        }
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new IslandMenu(id, inv, c),
                Component.literal("Insel waehlen")));
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (slot >= 0 && slot < Game.MAX_ISLANDS && player instanceof ServerPlayer sp) {
            Game.I.claim(sp, slot + 1);
            sp.closeContainer();
        } else {
            broadcastFullState();
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
