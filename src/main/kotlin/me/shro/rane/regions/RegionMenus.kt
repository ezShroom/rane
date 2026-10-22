package me.shro.rane.regions

import me.shro.rane.core.Menus
import me.shro.rane.core.Menus.button
import me.shro.rane.core.Perms
import me.shro.rane.core.displayName
import me.shro.rane.core.txt
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands.literal
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Items
import java.util.UUID

/** `/rg`: everything about regions happens in these menus. */
object RegionMenus {
    private val data get() = Regions.store.data

    fun init() {
        CommandRegistrationCallback.EVENT.register { d, _, _ ->
            for (name in listOf("rg", "region")) {
                d.register(literal(name).requires(Perms.everyone("rane.regions.command")).executes { ctx ->
                    main(ctx.source.playerOrException); 1
                })
            }
        }
    }

    private fun name(uuid: String): String = runCatching {
        me.shro.rane.Rane.server.services().nameToIdCache().get(UUID.fromString(uuid)).map { it.name() }.orElse(uuid.take(8))
    }.getOrDefault(uuid.take(8))

    fun main(p: ServerPlayer) {
        val gui = Menus.chest(p, 3, "§8Regions")
        val sel = Selection.active[p.uuid]
        if (sel == null) {
            gui.setSlot(10, button(Items.WRITABLE_BOOK, "§aCreate region", "§7Start selecting a region.") { gui.close(); Selection.start(p) })
        } else {
            val problems = Selection.problems(p, sel)
            val r = Selection.toRegion(p, sel)
            val currency = Menus.item(Regions.config.currency)
            if (problems.isEmpty() && r != null) {
                val price = Regions.cost(r.maxX - r.minX + 1, r.maxZ - r.minZ + 1)
                gui.setSlot(10, button(Items.CONCRETE.lime(), "§aBuy this region", "§7Costs §b$price ${currency.displayName}§7 (refunded on delete).") {
                    gui.close()
                    buy(p, sel, price)
                })
            } else {
                gui.setSlot(10, button(Items.CONCRETE.red(), "§cCan't create this region yet", *problems.map { "§7$it" }.toTypedArray()))
            }
            gui.setSlot(19, button(Items.BARRIER, "§cCancel selection") { Selection.cancel(p); main(p) })
        }
        val here = Regions.regionAt(p.level() as ServerLevel, p.blockPosition())
        if (here != null) {
            gui.setSlot(12, button(Items.FILLED_MAP, "§eCurrent region: §b${here.name}", "§7Owner: ${name(here.owner)}") { region(p, here) })
            Regions.groupOf(here)?.let { g -> gui.setSlot(13, button(Items.GLOBE_BANNER_PATTERN, "§eRegion group: §b${g.name}") { group(p, g) }) }
        }
        gui.setSlot(15, button(Items.MAP, "§eYour regions") { regionList(p) })
        gui.setSlot(16, button(Items.BOOKSHELF, "§eRegion groups you manage") { groupList(p) })
        gui.setSlot(25, button(Items.WRITABLE_BOOK, "§aNew region group") {
            Menus.prompt(p, "Name the group", "") { n ->
                val g = RegionGroup.fresh(n, p.uuid)
                Regions.store.edit { groups[g.id] = g }
                group(p, g)
            }
        })
        gui.open()
    }

    private fun buy(p: ServerPlayer, sel: Selection.Sel, price: Int) {
        if (Selection.problems(p, sel).isNotEmpty()) return main(p)
        val r = Selection.toRegion(p, sel) ?: return
        val currency = Menus.item(Regions.config.currency)
        if (!Menus.pay(p, currency, price)) {
            p.sendSystemMessage(txt("§cYou need $price ${currency.displayName}."))
            return
        }
        Menus.prompt(p, "Name your region", "${p.gameProfile.name()}'s region") { n ->
            val region = r.copy(id = UUID.randomUUID().toString(), name = n, group = Regions.defaultGroup(p).id, paid = price)
            Regions.store.edit { regions[region.id] = region }
            Regions.invalidateIndex()
            Selection.cancel(p)
            p.sendSystemMessage(txt("§aRegion §b$n§a created."))
        }
    }

    private fun regionList(p: ServerPlayer) {
        val mine = data.regions.values.filter { r -> r.owner == p.uuid.toString() || Regions.groupOf(r)?.let { Regions.mayAdministrate(p, it) } == true }
        val gui = Menus.chest(p, 6, "§8Your regions")
        mine.take(45).forEachIndexed { i, r ->
            gui.setSlot(i, button(Items.FILLED_MAP, "§b${r.name}", "§7${r.dimension}", "§7${r.minX},${r.minY},${r.minZ} → ${r.maxX},${r.maxY},${r.maxZ}") { region(p, r) })
        }
        gui.setSlot(49, button(Items.ARROW, "§eBack") { main(p) })
        gui.open()
    }

    private fun groupList(p: ServerPlayer) {
        val mine = data.groups.values.filter { Regions.mayAdministrate(p, it) }
        val gui = Menus.chest(p, 6, "§8Region groups")
        mine.take(45).forEachIndexed { i, g -> gui.setSlot(i, button(Items.BOOKSHELF, "§b${g.name}") { group(p, g) }) }
        gui.setSlot(49, button(Items.ARROW, "§eBack") { main(p) })
        gui.open()
    }

    fun region(p: ServerPlayer, r: Region) {
        val g = Regions.groupOf(r)
        val admin = r.owner == p.uuid.toString() || (g != null && Regions.mayAdministrate(p, g))
        val gui = Menus.chest(p, 3, "§8Region: ${r.name}")
        gui.setSlot(4, button(Items.FILLED_MAP, "§b${r.name}", "§7Owner: ${name(r.owner)}", "§7Group: ${g?.name ?: "?"}",
            "§7${r.minX},${r.minY},${r.minZ} → ${r.maxX},${r.maxY},${r.maxZ}"))
        if (admin) {
            gui.setSlot(10, button(Items.NAME_TAG, "§eRename") {
                Menus.prompt(p, "Rename region", r.name) { n -> Regions.store.edit { r.name = n }; region(p, r) }
            })
            gui.setSlot(12, button(Items.GLOBE_BANNER_PATTERN, "§eAssign region group", "§7Current: ${g?.name ?: "?"}") { assignGroup(p, r) })
            if (g != null) gui.setSlot(14, button(Items.BOOKSHELF, "§eOpen region group") { group(p, g) })
        }
        if (r.owner == p.uuid.toString() || Regions.bypass(p)) {
            gui.setSlot(16, button(Items.TNT, "§cDelete region", "§7Refunds ${r.paid} ${Menus.item(Regions.config.currency).displayName}.") {
                Menus.confirm(p, "Delete ${r.name}?", {
                    Regions.store.edit { regions.remove(r.id) }
                    Regions.invalidateIndex()
                    Rane_refund(p, r)
                    p.sendSystemMessage(txt("§eRegion ${r.name} deleted."))
                }, { region(p, r) })
            })
        }
        gui.setSlot(22, button(Items.ARROW, "§eBack") { main(p) })
        gui.open()
    }

    private fun Rane_refund(p: ServerPlayer, r: Region) {
        if (r.owner == p.uuid.toString()) Menus.refund(p, Menus.item(Regions.config.currency), r.paid)
    }

    private fun assignGroup(p: ServerPlayer, r: Region) {
        val gui = Menus.chest(p, 6, "§8Assign group to ${r.name}")
        data.groups.values.filter { Regions.mayAdministrate(p, it) }.take(45).forEachIndexed { i, g ->
            gui.setSlot(i, button(if (g.id == r.group) Items.ENCHANTED_BOOK else Items.BOOK, "§b${g.name}") {
                Regions.store.edit { r.group = g.id }
                region(p, r)
            })
        }
        gui.setSlot(49, button(Items.ARROW, "§eBack") { region(p, r) })
        gui.open()
    }

    fun group(p: ServerPlayer, g: RegionGroup) {
        if (!Regions.mayAdministrate(p, g)) {
            p.sendSystemMessage(txt("§cYou don't manage this region group."))
            return
        }
        val gui = Menus.chest(p, 5, "§8Group: ${g.name}")
        gui.setSlot(0, button(Items.NAME_TAG, "§eRename") { Menus.prompt(p, "Rename group", g.name) { n -> Regions.store.edit { g.name = n }; group(p, g) } })
        g.roles.take(7).forEachIndexed { i, role ->
            gui.setSlot(10 + i, button(Items.PLAYER_HEAD, "§bRole: ${role.name}",
                *RoleSetting.entries.map { "§7${it.label}: ${if (role.has(it)) "§aon" else "§coff"}" }.toTypedArray(),
                "§7Members: ${g.players.count { it.value == role.id }}") { role(p, g, role) })
        }
        gui.setSlot(17, button(Items.WRITABLE_BOOK, "§aNew role") {
            Menus.prompt(p, "Name the role", "") { n ->
                val role = Role(UUID.randomUUID().toString().take(8), n, mutableMapOf("USE" to true))
                Regions.store.edit { g.roles += role }
                role(p, g, role)
            }
        })
        EnvSetting.entries.forEachIndexed { i, s ->
            val on = g.env(s)
            gui.setSlot(27 + i, button(if (on) Items.DYE.lime() else Items.DYE.gray(), "§e${s.label}: ${if (on) "§aallowed" else "§cprevented"}", "§7Click to toggle") {
                Regions.store.edit { g.environment[s.name] = !on }
                group(p, g)
            })
        }
        val used = data.regions.values.count { it.group == g.id }
        if (g.owner == p.uuid.toString() && data.defaultGroups[p.uuid.toString()] != g.id) {
            gui.setSlot(35, button(Items.TNT, "§cDelete group", if (used > 0) "§7Still used by $used region(s)." else "§7Not used by any region.") {
                if (used > 0) return@button
                Menus.confirm(p, "Delete ${g.name}?", { Regions.store.edit { groups.remove(g.id) }; main(p) }, { group(p, g) })
            })
        }
        gui.setSlot(40, button(Items.ARROW, "§eBack") { main(p) })
        gui.open()
    }

    private fun role(p: ServerPlayer, g: RegionGroup, role: Role) {
        val gui = Menus.chest(p, 6, "§8Role ${role.name} (${g.name})")
        RoleSetting.entries.forEachIndexed { i, s ->
            val on = role.has(s)
            val locked = role.id == RegionGroup.ADMINS && s == RoleSetting.ADMIN
            gui.setSlot(i, button(if (on) Items.DYE.lime() else Items.DYE.gray(), "§e${s.label}: ${if (on) "§aon" else "§coff"}", if (locked) "§7Admins always administrate" else "§7Click to toggle") {
                if (locked) return@button
                Regions.store.edit { role.settings[s.name] = !on }
                role(p, g, role)
            })
        }
        val members = g.players.filter { it.value == role.id }.keys.toList()
        members.take(36).forEachIndexed { i, uuid ->
            gui.setSlot(9 + i, button(Items.PLAYER_HEAD, "§b${name(uuid)}", "§7Click to remove from this role") {
                if (uuid == g.owner) return@button
                Regions.store.edit { g.players.remove(uuid) }
                role(p, g, role)
            })
        }
        if (role.id != RegionGroup.OTHERS) {
            gui.setSlot(46, button(Items.WRITABLE_BOOK, "§aAdd player") {
                Menus.prompt(p, "Player name", "") { n ->
                    val profile = me.shro.rane.Rane.server.playerList.getPlayerByName(n)?.let { it.uuid to it.gameProfile.name() }
                        ?: me.shro.rane.Rane.server.services().nameToIdCache().get(n).map { it.id() to it.name() }.orElse(null)
                    if (profile == null) p.sendSystemMessage(txt("§cUnknown player $n."))
                    else Regions.store.edit { g.players[profile.first.toString()] = role.id }
                    role(p, g, role)
                }
            })
        }
        gui.setSlot(47, button(Items.NAME_TAG, "§eRename role") { Menus.prompt(p, "Rename role", role.name) { n -> Regions.store.edit { role.name = n }; role(p, g, role) } })
        if (role.id !in setOf(RegionGroup.ADMINS, RegionGroup.FRIENDS, RegionGroup.OTHERS)) {
            gui.setSlot(48, button(Items.TNT, "§cDelete role", "§7Members fall back to [Others]") {
                Regions.store.edit { g.roles.remove(role); g.players.values.removeIf { it == role.id } }
                group(p, g)
            })
        }
        gui.setSlot(49, button(Items.ARROW, "§eBack") { group(p, g) })
        gui.open()
    }
}
