package me.shro.rane.portals

import me.shro.rane.core.Menus
import me.shro.rane.core.Menus.button
import me.shro.rane.core.notifySound
import me.shro.rane.core.txt
import me.shro.rane.regions.Regions
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Items

/** Console menu: choose a destination; owners also get the settings. */
object PortalMenus {
    private val data get() = Portals.store.data

    fun init() {}

    private fun canManage(p: ServerPlayer, portal: Portal): Boolean {
        if (portal.owner == p.uuid.toString() || Regions.bypass(p)) return true
        if (!me.shro.rane.Rane.isEnabled("regions")) return false
        val level = Portals.level(portal) ?: return false
        val region = Regions.regionAt(level, portal.origin.pos()) ?: return false
        return Regions.groupOf(region)?.let { Regions.mayAdministrate(p, it) } == true
    }

    fun console(p: ServerPlayer, portal: Portal, page: Int = 0) {
        if (!PortalLogic.mayUse(p, portal)) {
            p.sendOverlayMessage(txt("§cYou may not use this portal."))
            return
        }
        val targets = data.portals.values.filter { PortalLogic.visible(p, portal, it) }.sortedBy { it.name.lowercase() }
        val pages = maxOf(1, (targets.size + 44) / 45)
        val pg = page.coerceIn(0, pages - 1)
        val gui = Menus.chest(p, 6, "§8${portal.name} §7(${pg + 1}/$pages)")
        targets.drop(pg * 45).take(45).forEachIndexed { i, t ->
            val selected = portal.target == t.id
            val b = button(PortalLogic.iconOf(t), "${if (selected) "§a▶ " else "§b"}${t.name}",
                "§7${t.dimension.removePrefix("minecraft:")} · ${t.origin.x}, ${t.origin.y}, ${t.origin.z}",
                "§7${t.visibility.label}", if (selected) "§aSelected" else "§7Click to select") {
                if (portal.id in PortalLogic.active) {
                    p.sendOverlayMessage(txt("§7Wait for the portal to close first."))
                    return@button
                }
                Portals.store.edit { portal.target = t.id }
                PortalLogic.refreshDisplays(portal)
                p.notifySoundClick()
                console(p, portal, pg)
            }
            if (selected) b.glow()
            gui.setSlot(i, b)
        }
        if (pg > 0) gui.setSlot(45, button(Items.ARROW, "§ePrevious page") { console(p, portal, pg - 1) })
        if (canManage(p, portal)) gui.setSlot(49, button(Items.COMPARATOR, "§eSettings") { settings(p, portal) })
        if (pg < pages - 1) gui.setSlot(53, button(Items.ARROW, "§eNext page") { console(p, portal, pg + 1) })
        gui.open()
    }

    private fun ServerPlayer.notifySoundClick() = notifySound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 0.6f, 1.2f)

    private fun settings(p: ServerPlayer, portal: Portal) {
        val gui = Menus.chest(p, 3, "§8Settings: ${portal.name}")
        gui.setSlot(10, button(Items.NAME_TAG, "§eRename") {
            Menus.prompt(p, "Rename portal", portal.name) { n -> Portals.store.edit { portal.name = n }; settings(p, portal) }
        })
        gui.setSlot(11, button(PortalLogic.iconOf(portal), "§eIcon", "§7Click to use the item in your main hand") {
            val held = p.mainHandItem
            if (held.isEmpty) return@button
            Portals.store.edit { portal.icon = PortalLogic.encodeIcon(held) }
            data.portals.values.filter { it.target == portal.id }.forEach { PortalLogic.refreshDisplays(it) }
            settings(p, portal)
        })
        gui.setSlot(12, button(Items.SPYGLASS, "§eVisibility: §b${portal.visibility.label}", "§7Click to cycle") {
            val all = Visibility.entries
            Portals.store.edit { portal.visibility = all[(all.indexOf(portal.visibility) + 1) % all.size] }
            settings(p, portal)
        })
        gui.setSlot(13, button(if (portal.orientationLocked) Items.DYE.lime() else Items.DYE.gray(),
            "§eOrientation lock: ${if (portal.orientationLocked) "§aon" else "§coff"}", "§7When on, travellers always exit at the front.") {
            Portals.store.edit { portal.orientationLocked = !portal.orientationLocked }
            settings(p, portal)
        })
        gui.setSlot(14, button(if (portal.targetLocked) Items.DYE.lime() else Items.DYE.gray(),
            "§eTarget lock: ${if (portal.targetLocked) "§aon" else "§coff"}", "§7Keep a private destination selected after travelling.") {
            Portals.store.edit { portal.targetLocked = !portal.targetLocked }
            settings(p, portal)
        })
        if (portal.owner == p.uuid.toString() || Regions.bypass(p)) {
            gui.setSlot(16, button(Items.TNT, "§cDelete portal", "§7The frame stays; the origin block is not refunded.") {
                Menus.confirm(p, "Delete ${portal.name}?", {
                    PortalLogic.delete(portal)
                    p.sendSystemMessage(txt("§ePortal ${portal.name} deleted."))
                }, { settings(p, portal) })
            })
        }
        gui.setSlot(22, button(Items.ARROW, "§eBack") { console(p, portal) })
        gui.open()
    }
}
