/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.event.*
import net.ccbluex.liquidbounce.event.EventManager.callEvent
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.modules.misc.AntiBot.isBot
import net.ccbluex.liquidbounce.features.module.modules.misc.Teams
import net.ccbluex.liquidbounce.features.module.modules.player.Blink
import net.ccbluex.liquidbounce.features.module.modules.render.FreeCam
import net.ccbluex.liquidbounce.utils.CooldownHelper.getAttackCooldownProgress
import net.ccbluex.liquidbounce.utils.CooldownHelper.resetLastAttackedTicks
import net.ccbluex.liquidbounce.utils.EntityUtils.targetAnimals
import net.ccbluex.liquidbounce.utils.EntityUtils.targetDead
import net.ccbluex.liquidbounce.utils.EntityUtils.targetInvisible
import net.ccbluex.liquidbounce.utils.EntityUtils.targetMobs
import net.ccbluex.liquidbounce.utils.EntityUtils.targetPlayer
import net.ccbluex.liquidbounce.utils.item.ItemUtils.isConsumingItem
import net.ccbluex.liquidbounce.utils.MovementUtils.isMoving
import net.ccbluex.liquidbounce.utils.PacketUtils.sendPacket
import net.ccbluex.liquidbounce.utils.PacketUtils.sendPackets
import net.ccbluex.liquidbounce.utils.RaycastUtils.raycastEntity
import net.ccbluex.liquidbounce.utils.Rotation
import net.ccbluex.liquidbounce.utils.RotationUtils.getCenter
import net.ccbluex.liquidbounce.utils.RotationUtils.getRotationDifference
import net.ccbluex.liquidbounce.utils.RotationUtils.isRotationFaced
import net.ccbluex.liquidbounce.utils.RotationUtils.limitAngleChange
import net.ccbluex.liquidbounce.utils.RotationUtils.searchCenter
import net.ccbluex.liquidbounce.utils.RotationUtils.setTargetRotation
import net.ccbluex.liquidbounce.utils.RotationUtils.targetRotation
import net.ccbluex.liquidbounce.utils.RotationUtils.toRotation
import net.ccbluex.liquidbounce.utils.extensions.*
import net.ccbluex.liquidbounce.utils.misc.RandomUtils.nextFloat
import net.ccbluex.liquidbounce.utils.misc.RandomUtils.nextInt
import net.ccbluex.liquidbounce.utils.render.RenderUtils.drawPlatform
import net.ccbluex.liquidbounce.utils.timer.MSTimer
import net.ccbluex.liquidbounce.utils.timer.TimeUtils.randomClickDelay
import net.ccbluex.liquidbounce.value.BoolValue
import net.ccbluex.liquidbounce.value.FloatValue
import net.ccbluex.liquidbounce.value.IntegerValue
import net.ccbluex.liquidbounce.value.ListValue
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.client.gui.inventory.GuiInventory
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.item.EntityArmorStand
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemAxe
import net.minecraft.item.ItemSword
import net.minecraft.network.play.client.*
import net.minecraft.network.play.client.C02PacketUseEntity.Action.ATTACK
import net.minecraft.network.play.client.C02PacketUseEntity.Action.INTERACT
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action.RELEASE_USE_ITEM
import net.minecraft.network.play.client.C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT
import net.minecraft.potion.Potion
import net.minecraft.util.BlockPos
import net.minecraft.util.EnumFacing
import net.minecraft.world.WorldSettings
import org.lwjgl.input.Keyboard
import java.awt.Color
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

object KillAura : Module("KillAura", ModuleCategory.COMBAT, Keyboard.KEY_R) {
    /**
     * OPTIONS
     */

    private val simulateCooldown by BoolValue("SimulateCooldown", false)

    // CPS - Attack speed
    private val maxCPSValue = object : IntegerValue("MaxCPS", 8, 1..20) {
        override fun onChange(oldValue: Int, newValue: Int) = newValue.coerceAtLeast(minCPS)

        override fun onChanged(oldValue: Int, newValue: Int) {
            attackDelay = randomClickDelay(minCPS, newValue)
        }

        override fun isSupported() = !simulateCooldown
    }
    private val maxCPS by maxCPSValue

    private val minCPS: Int by object : IntegerValue("MinCPS", 5, 1..20) {
        override fun onChange(oldValue: Int, newValue: Int) = newValue.coerceAtMost(maxCPS)

        override fun onChanged(oldValue: Int, newValue: Int) {
            attackDelay = randomClickDelay(newValue, maxCPS)
        }

        override fun isSupported() = !maxCPSValue.isMinimal() && !simulateCooldown
    }

    private val hurtTime by IntegerValue("HurtTime", 10, 0..10) { !simulateCooldown }

    private val clickOnly by BoolValue("ClickOnly", false)

    // Range
    // TODO: Make block range independent from attack range
    private val range: Float by object : FloatValue("Range", 3.7f, 1f..8f) {
        override fun onChanged(oldValue: Float, newValue: Float) {
            blockRange = blockRange.coerceAtMost(newValue)
        }
    }
    private val scanRange by FloatValue("ScanRange", 2f, 0f..10f)
    private val throughWallsRange by FloatValue("ThroughWallsRange", 3f, 0f..8f)
    private val rangeSprintReduction by FloatValue("RangeSprintReduction", 0f, 0f..0.4f)

    // Modes
    private val priority by ListValue(
        "Priority", arrayOf(
            "Health",
            "Distance",
            "Direction",
            "LivingTime",
            "Armor",
            "HurtResistance",
            "HurtTime",
            "HealthAbsorption",
            "RegenAmplifier"
        ), "Distance"
    )
    private val targetMode by ListValue("TargetMode", arrayOf("Single", "Switch", "Multi"), "Switch")
    private val limitedMultiTargets by IntegerValue("LimitedMultiTargets", 0, 0..50) { targetMode == "Multi" }

    // Delay
    private val switchDelay by IntegerValue("SwitchDelay", 15, 1..1000) { targetMode == "Switch" }

    // Bypass
    private val swing by BoolValue("Swing", true)
    private val keepSprint by BoolValue("KeepSprint", true)

    // AutoBlock
    private val autoBlock by ListValue("AutoBlock", arrayOf("Off", "Packet", "AfterTick", "Fake"), "Packet")
    private val interactAutoBlock by BoolValue("InteractAutoBlock", true) { autoBlock !in arrayOf("Off", "Fake") }

    // AutoBlock conditions
    private val smartAutoBlock by BoolValue("SmartAutoBlock", false) { autoBlock != "Off" }

    // Ignore all blocking conditions, except for block rate, when standing still
    private val forceBlock by BoolValue("ForceBlockWhenStill", true) { smartAutoBlock }

    // Don't block if target isn't holding a sword or an axe
    private val checkWeapon by BoolValue("CheckEnemyWeapon", true) { smartAutoBlock }

    // TODO: Make block range independent from attack range
    private var blockRange by object : FloatValue("BlockRange", range, 1f..8f) {
        override fun isSupported() = smartAutoBlock

        override fun onChange(oldValue: Float, newValue: Float) = newValue.coerceAtMost(this@KillAura.range)
    }

    // Don't block when you can't get damaged
    private val maxOwnHurtTime by IntegerValue("MaxOwnHurtTime", 3, 0..10) { smartAutoBlock }

    // Don't block if target isn't looking at you
    private val maxDirectionDiff by FloatValue("MaxOpponentDirectionDiff", 60f, 30f..180f) { smartAutoBlock }

    // Don't block if target is swinging an item and therefore cannot attack
    private val maxSwingProgress by IntegerValue("MaxOpponentSwingProgress", 1, 0..5) { smartAutoBlock }
    private val blockRate by IntegerValue("BlockRate", 100, 1..100) { autoBlock != "Off" }

    // Turn Speed
    private val maxTurnSpeedValue = object : FloatValue("MaxTurnSpeed", 180f, 0f..180f) {
        override fun onChange(oldValue: Float, newValue: Float) = newValue.coerceAtLeast(minTurnSpeed)
    }
    private val maxTurnSpeed by maxTurnSpeedValue

    private val minTurnSpeed: Float by object : FloatValue("MinTurnSpeed", 180f, 0f..180f) {
        override fun onChange(oldValue: Float, newValue: Float) = newValue.coerceAtMost(maxTurnSpeed)

        override fun isSupported() = !maxTurnSpeedValue.isMinimal()
    }

    // Raycast
    private val raycastValue = BoolValue("RayCast", true) { !maxTurnSpeedValue.isMinimal() }
    private val raycast by raycastValue

    private val raycastIgnored by BoolValue("RayCastIgnored", false) { raycastValue.isActive() }
    private val livingRaycast by BoolValue("LivingRayCast", true) { raycastValue.isActive() }

    // Bypass
    // AAC value also modifies target selection a bit, not just rotations, but it is minor
    private val aacValue = BoolValue("AAC", false) { !maxTurnSpeedValue.isMinimal() }
    private val aac by aacValue

    private val keepRotationTicks by object : IntegerValue("KeepRotationTicks", 5, 1..20) {
        override fun isSupported() = !aacValue.isActive()

        override fun onChange(oldValue: Int, newValue: Int) = newValue.coerceAtLeast(minimum)
    }

    private val angleThresholdUntilReset by FloatValue("AngleThresholdUntilReset", 5f, 0.1f..180f)

    private val micronizedValue = BoolValue("Micronized", true) { !maxTurnSpeedValue.isMinimal() }
    private val micronized by micronizedValue

    private val micronizedStrength by FloatValue("MicronizedStrength", 0.8f, 0.2f..2f) { micronizedValue.isActive() }

    // Rotations
    private val silentRotationValue = BoolValue("SilentRotation", true) { !maxTurnSpeedValue.isMinimal() }
    private val silentRotation by silentRotationValue
    private val rotationStrafe by ListValue(
        "Strafe", arrayOf("Off", "Strict", "Silent"), "Off"
    ) { silentRotationValue.isActive() }
    private val randomCenter by BoolValue("RandomCenter", true) { !maxTurnSpeedValue.isMinimal() }
    private val outborder by BoolValue("Outborder", false) { !maxTurnSpeedValue.isMinimal() }
    private val fov by FloatValue("FOV", 180f, 0f..180f)

    // Predict
    private val predictValue = BoolValue("Predict", true) { !maxTurnSpeedValue.isMinimal() }
    private val predict by predictValue

    private val maxPredictSizeValue = object : FloatValue("MaxPredictSize", 1f, 0.1f..5f) {
        override fun onChange(oldValue: Float, newValue: Float) = newValue.coerceAtLeast(minPredictSize)

        override fun isSupported() = predictValue.isActive()
    }
    private val maxPredictSize by maxPredictSizeValue

    private val minPredictSize: Float by object : FloatValue("MinPredictSize", 1f, 0.1f..5f) {
        override fun onChange(oldValue: Float, newValue: Float) = newValue.coerceAtMost(maxPredictSize)

        override fun isSupported() = predictValue.isActive() && !maxPredictSizeValue.isMinimal()
    }

    // Bypass
    private val failRate by IntegerValue("FailRate", 0, 0..99)
    private val fakeSwing by BoolValue("FakeSwing", true) { swing }
    private val noInventoryAttack by BoolValue("NoInvAttack", false)
    private val noConsumeAttack by ListValue("NoConsumeAttack", arrayOf("Off", "NoHits", "NoRotation"), "Off")
    private val noInventoryDelay by IntegerValue("NoInvDelay", 200, 0..500) { noInventoryAttack }

    // Visuals
    private val mark by BoolValue("Mark", true)
    private val fakeSharp by BoolValue("FakeSharp", true)

    /**
     * MODULE
     */

    // Target
    var target: EntityLivingBase? = null
    private var currentTarget: EntityLivingBase? = null
    private var hitable = false
    private val prevTargetEntities = mutableListOf<Int>()

    // Attack delay
    private val attackTimer = MSTimer()
    private var attackDelay = 0
    private var clicks = 0

    // Container Delay
    private var containerOpen = -1L

    // Block status
    var renderBlocking = false
    var blockStatus = false
    private var blockStopInDead = false

    /**
     * Enable kill aura module
     */
    override fun onEnable() {
        mc.thePlayer ?: return
        mc.theWorld ?: return

        updateTarget()
    }

    /**
     * Disable kill aura module
     */
    override fun onDisable() {
        target = null
        currentTarget = null
        hitable = false
        prevTargetEntities.clear()
        attackTimer.reset()
        clicks = 0

        stopBlocking()
    }

    /**
     * Motion event
     */
    @EventTarget
    fun onMotion(event: MotionEvent) {
        if (event.eventState == EventState.POST) {
            update()

            target ?: return
            currentTarget ?: return

            // Update hitable
            updateHitable()

            // AutoBlock
            if (canBlock) {
                when (autoBlock) {
                    "AfterTick" -> startBlocking(currentTarget!!, hitable)
                    "Fake" -> startBlocking(currentTarget!!, hitable, fake = true)
                }
            }

            return
        }
    }

    fun update() {
        if (cancelRun || (noInventoryAttack && (mc.currentScreen is GuiContainer || System.currentTimeMillis() - containerOpen < noInventoryDelay))) return

        // Update target
        updateTarget()

        if (target == null && !blockStopInDead) {
            blockStopInDead = true
            stopBlocking()
            return
        }

        target?.let {
            if (mc.thePlayer.getDistanceToEntityBox(it) > range) {
                stopBlocking()
            }
        }

        // Target
        currentTarget = target

        /*
        TODO: Remove? -> currentTarget = target = currentTarget

        if (targetMode != "Switch" && isEnemy(currentTarget))
            target = currentTarget
         */
    }

    /**
     * Update event
     */
    @EventTarget
    fun onUpdate(event: UpdateEvent) {
        if (clickOnly && !mc.gameSettings.keyBindAttack.isKeyDown) return

        if (cancelRun) {
            target = null
            currentTarget = null
            hitable = false
            stopBlocking()
            return
        }

        if (noInventoryAttack && (mc.currentScreen is GuiContainer || System.currentTimeMillis() - containerOpen < noInventoryDelay)) {
            target = null
            currentTarget = null
            hitable = false
            if (mc.currentScreen is GuiContainer) containerOpen = System.currentTimeMillis()
            return
        }

        if (simulateCooldown && getAttackCooldownProgress() < 1f) {
            return
        }

        if (target != null && currentTarget != null) {
            while (clicks > 0) {
                runAttack()
                clicks--
            }
        }
    }

    /**
     * Render event
     */
    @EventTarget
    fun onRender3D(event: Render3DEvent) {
        if (cancelRun) {
            target = null
            currentTarget = null
            hitable = false
            stopBlocking()
            return
        }

        if (noInventoryAttack && (mc.currentScreen is GuiContainer || System.currentTimeMillis() - containerOpen < noInventoryDelay)) {
            target = null
            currentTarget = null
            hitable = false
            if (mc.currentScreen is GuiContainer) containerOpen = System.currentTimeMillis()
            return
        }

        target ?: return

        if (mark && targetMode != "Multi") drawPlatform(
            target!!, if (hitable) Color(37, 126, 255, 70) else Color(255, 0, 0, 70)
        )

        if (currentTarget != null && attackTimer.hasTimePassed(attackDelay) && currentTarget!!.hurtTime <= hurtTime) {
            clicks++
            attackTimer.reset()
            attackDelay = randomClickDelay(minCPS, maxCPS)
        }
    }

    /**
     * Handle entity move
     */
    @EventTarget
    fun onEntityMove(event: EntityMovementEvent) {
        val movedEntity = event.movedEntity

        if (target == null || movedEntity != currentTarget) return

        updateHitable()
    }

    /**
     * Attack enemy
     */
    private fun runAttack() {
        val target = target ?: return
        val currentTarget = currentTarget ?: return

        val thePlayer = mc.thePlayer ?: return
        val theWorld = mc.theWorld ?: return

        if (noConsumeAttack == "NoHits" && isConsumingItem()) {
            return
        }

        // Settings
        val failRate = failRate
        val swing = swing
        val multi = targetMode == "Multi"
        val openInventory = aac && mc.currentScreen is GuiInventory
        val failHit = failRate > 0 && nextInt(endExclusive = 100) <= failRate

        // Close inventory when open
        if (openInventory) sendPacket(C0DPacketCloseWindow())

        updateHitable()

        // Check if enemy is not hitable or check failrate
        if (!hitable || failHit) {
            if (swing && (fakeSwing || failHit)) thePlayer.swingItem()
        } else {
            blockStopInDead = false
            // Attack
            if (!multi) {
                attackEntity(currentTarget)
            } else {
                var targets = 0

                for (entity in theWorld.loadedEntityList) {
                    val distance = thePlayer.getDistanceToEntityBox(entity)

                    if (entity is EntityLivingBase && isEnemy(entity) && distance <= getRange(entity)) {
                        attackEntity(entity)

                        targets += 1

                        if (limitedMultiTargets != 0 && limitedMultiTargets <= targets) break
                    }
                }
            }

            prevTargetEntities += if (aac) target.entityId else currentTarget.entityId

            if (target == currentTarget) this.target = null
        }

        if (targetMode.equals("Switch", ignoreCase = true) && attackTimer.hasTimePassed((switchDelay).toLong())) {
            if (switchDelay != 0) {
                prevTargetEntities += if (aac) target.entityId else currentTarget.entityId
                attackTimer.reset()
            }
        }

        // Open inventory
        if (openInventory) sendPacket(C16PacketClientStatus(OPEN_INVENTORY_ACHIEVEMENT))
    }

    /**
     * Update current target
     */
    private fun updateTarget() {
        // Reset fixed target to null
        target = null

        // Settings
        val hurtTime = hurtTime
        val fov = fov
        val switchMode = targetMode == "Switch"

        // Find possible targets
        val targets = mutableListOf<EntityLivingBase>()

        val theWorld = mc.theWorld
        val thePlayer = mc.thePlayer

        for (entity in theWorld.loadedEntityList) {
            if (entity !is EntityLivingBase || !isEnemy(entity) || (switchMode && entity.entityId in prevTargetEntities)) continue

            var distance = thePlayer.getDistanceToEntityBox(entity)
            if (Backtrack.state) {
                val trackedDistance = Backtrack.getNearestTrackedDistance(entity)

                if (distance > trackedDistance) {
                    distance = trackedDistance
                }
            }
            val entityFov = getRotationDifference(entity)

            if (distance <= maxRange && (fov == 180F || entityFov <= fov) && entity.hurtTime <= hurtTime) {
                targets += entity
            }
        }

        // Sort targets by priority
        when (priority.lowercase()) {
            "distance" -> targets.sortBy { thePlayer.getDistanceToEntityBox(it) } // Sort by distance
            "health" -> targets.sortBy { it.health } // Sort by health
            "direction" -> targets.sortBy { getRotationDifference(it) } // Sort by FOV
            "livingtime" -> targets.sortBy { -it.ticksExisted } // Sort by existence
            "armor" -> targets.sortBy { it.totalArmorValue } // Sort by armor
            "hurtresistance" -> targets.sortBy { it.hurtResistantTime } // Sort by armor hurt time
            "hurttime" -> targets.sortBy { it.hurtTime } // Sort by hurt time
            "healthabsorption" -> targets.sortBy { it.health + it.absorptionAmount } // Sort by full health with absorption effect
            "regenamplifier" -> targets.sortBy {
                if (it.isPotionActive(Potion.regeneration)) it.getActivePotionEffect(
                    Potion.regeneration
                ).amplifier else -1
            }

        }

        // Find best target
        for (entity in targets) {
            // Update rotations to current target
            if (!updateRotations(entity)) {
                var success = false
                Backtrack.loopThroughBacktrackData(entity) {
                    if (updateRotations(entity)) {
                        success = true
                        return@loopThroughBacktrackData true
                    }

                    return@loopThroughBacktrackData false
                }

                if (!success) {
                    // when failed then try another target
                    continue
                }
            }

            // Set target to current entity
            target = entity
            return
        }

        // Cleanup last targets when no target found and try again
        if (prevTargetEntities.isNotEmpty()) {
            prevTargetEntities.clear()
            updateTarget()
        }
    }

    /**
     * Check if [entity] is selected as enemy with current target options and other modules
     */
    private fun isEnemy(entity: Entity?): Boolean {
        if (entity is EntityLivingBase && (targetDead || isAlive(entity)) && entity != mc.thePlayer) {
            if (!targetInvisible && entity.isInvisible) return false

            if (targetPlayer && entity is EntityPlayer) {
                if (entity.isSpectator || isBot(entity)) return false

                if (entity.isClientFriend() && !NoFriends.state) return false

                return !Teams.state || !Teams.isInYourTeam(entity)
            }

            return targetMobs && entity.isMob() || targetAnimals && entity.isAnimal()
        }

        return false
    }

    /**
     * Attack [entity]
     */
    private fun attackEntity(entity: EntityLivingBase) {
        // Stop blocking
        val thePlayer = mc.thePlayer

        if (thePlayer.isBlocking || renderBlocking) stopBlocking()

        // Call attack event
        callEvent(AttackEvent(entity))

        // Attack target
        if (swing) thePlayer.swingItem()

        sendPacket(C02PacketUseEntity(entity, ATTACK))

        if (keepSprint) {
            // Critical Effect
            if (thePlayer.fallDistance > 0F && !thePlayer.onGround && !thePlayer.isOnLadder && !thePlayer.isInWater && !thePlayer.isPotionActive(
                    Potion.blindness
                ) && !thePlayer.isRiding
            ) thePlayer.onCriticalHit(entity)

            // Enchant Effect
            if (EnchantmentHelper.getModifierForCreature(
                    thePlayer.heldItem, entity.creatureAttribute
                ) > 0F
            ) thePlayer.onEnchantmentCritical(entity)
        } else {
            if (mc.playerController.currentGameType != WorldSettings.GameType.SPECTATOR) thePlayer.attackTargetEntityWithCurrentItem(
                entity
            )
        }

        // Extra critical effects
        repeat(3) {
            // Critical Effect
            if (thePlayer.fallDistance > 0F && !thePlayer.onGround && !thePlayer.isOnLadder && !thePlayer.isInWater && !thePlayer.isPotionActive(
                    Potion.blindness
                ) && thePlayer.ridingEntity == null || Criticals.state && Criticals.msTimer.hasTimePassed(Criticals.delay) && !thePlayer.isInWater && !thePlayer.isInLava && !thePlayer.isInWeb
            ) thePlayer.onCriticalHit(target)

            // Enchant Effect
            if (EnchantmentHelper.getModifierForCreature(
                    thePlayer.heldItem, target!!.creatureAttribute
                ) > 0f || fakeSharp
            ) thePlayer.onEnchantmentCritical(target)
        }

        //TODO: SHOULD THIS BE THIS? https://github.com/CCBlueX/LiquidBounce/blob/bb112eb53fdee22a974695a1dcaec3c6d9ec10eb/1.8.9-Forge/src/main/java/net/ccbluex/liquidbounce/features/module/modules/combat/KillAura.kt#L547
        /*

            // Start blocking after attack
        if (thePlayer.isBlocking || (autoBlock && canBlock)) {
            if (!(blockRate > 0 && Random().nextInt(100) <= blockRate))
                return

            if (delayedBlock)
                return

            startBlocking(entity, interactAutoBlock)
        }
         */
        // Start blocking after attack
        if (autoBlock == "Packet" && (thePlayer.isBlocking || canBlock)) startBlocking(
            entity, interactAutoBlock
        )

        resetLastAttackedTicks()
    }

    /**
     * Update killaura rotations to enemy
     */
    private fun updateRotations(entity: Entity): Boolean {
        if (maxTurnSpeedValue.isMinimal()) return true

        var boundingBox = entity.hitBox

        if (predict) {
            boundingBox = boundingBox.offset(
                (entity.posX - entity.prevPosX - (mc.thePlayer.posX - mc.thePlayer.prevPosX)) * nextFloat(
                    minPredictSize, maxPredictSize
                ), (entity.posY - entity.prevPosY - (mc.thePlayer.posY - mc.thePlayer.prevPosY)) * nextFloat(
                    minPredictSize, maxPredictSize
                ), (entity.posZ - entity.prevPosZ - (mc.thePlayer.posZ - mc.thePlayer.prevPosZ)) * nextFloat(
                    minPredictSize, maxPredictSize
                )
            )
        }

        val reachAccordingToDistance = if (mc.thePlayer.getDistanceToBox(boundingBox) > range) {
            maxRange
        } else {
            range
        }

        val (_, rotation) = searchCenter(
            boundingBox,
            outborder && !attackTimer.hasTimePassed(attackDelay / 2),
            randomCenter,
            predict,
            mc.thePlayer.getDistanceToBox(boundingBox) <= throughWallsRange,
            reachAccordingToDistance
        ) ?: return false

        // Get our current rotation. Otherwise, player rotation.
        val currentRotation = targetRotation ?: mc.thePlayer.rotation

        var limitedRotation = limitAngleChange(
            currentRotation, rotation, nextFloat(minTurnSpeed, maxTurnSpeed)
        )

        if (micronized) {
            // Is player facing the entity with current rotation?
            if (isRotationFaced(entity, maxRange.toDouble(), currentRotation)) {
                // Limit angle change but this time modify the turn speed.
                limitedRotation =
                    limitAngleChange(currentRotation, rotation, nextFloat(endInclusive = micronizedStrength))
            }
        }

        if (silentRotation) {
            setTargetRotation(
                limitedRotation,
                if (aac) 10 else keepRotationTicks,
                !(!silentRotation || rotationStrafe == "Off"),
                rotationStrafe == "Strict",
                minTurnSpeed to maxTurnSpeed,
                angleThresholdUntilReset
            )
        } else {
            limitedRotation.toPlayer(mc.thePlayer)
        }

        return true
    }

    /**
     * Check if enemy is hitable with current rotations
     */
    private fun updateHitable() {
        // Disable hitable check if turn speed is zero
        if (maxTurnSpeedValue.isMinimal()) {
            hitable = true
            return
        }

        val currentRotation = targetRotation ?: mc.thePlayer.rotation

        if (raycast) {
            val raycastedEntity =
                raycastEntity(range.toDouble(), currentRotation.yaw, currentRotation.pitch) { entity ->
                    (!livingRaycast || (entity is EntityLivingBase && entity !is EntityArmorStand)) && (isEnemy(
                        entity
                    ) || raycastIgnored || aac && mc.theWorld.getEntitiesWithinAABBExcludingEntity(
                        entity, entity.entityBoundingBox
                    ).isNotEmpty())
                }

            if (raycast && raycastedEntity != null && raycastedEntity is EntityLivingBase && (NoFriends.state || !(raycastedEntity is EntityPlayer && raycastedEntity.isClientFriend()))) currentTarget =
                raycastedEntity

            hitable = currentTarget == raycastedEntity
        } else hitable = isRotationFaced(currentTarget!!, range.toDouble(), currentRotation)
    }

    /**
     * Start blocking
     */
    private fun startBlocking(interactEntity: Entity, interact: Boolean, fake: Boolean = false) {
        if (!fake) {
            if (!(blockRate > 0 && nextInt(endExclusive = 100) <= blockRate)) return

            if (interact) {
                val positionEye = mc.thePlayer.eyes

                val boundingBox = interactEntity.hitBox

                val (yaw, pitch) = targetRotation ?: Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch)
                val yawRad = -yaw.toRadians() - Math.PI
                val pitchRad = -pitch.toRadians()
                val yawCos = cos(yawRad)
                val yawSin = sin(yawRad)
                val pitchCos = -cos(pitchRad)
                val pitchSin = sin(pitchRad)
                val range = maxRange.toDouble()
                val lookAt =
                    positionEye.addVector(yawSin * pitchCos * range, pitchSin * range, yawCos * pitchCos * range)

                val movingObject = boundingBox.calculateIntercept(positionEye, lookAt) ?: return
                val hitVec = movingObject.hitVec

                sendPackets(
                    C02PacketUseEntity(interactEntity, hitVec - interactEntity.positionVector),
                    C02PacketUseEntity(interactEntity, INTERACT)
                )
            }

            sendPacket(C08PacketPlayerBlockPlacement(BlockPos(-1, -1, -1), 255, mc.thePlayer.heldItem, 0f, 0f, 0f))
            blockStatus = true
        }

        renderBlocking = true
    }


    /**
     * Stop blocking
     */
    private fun stopBlocking() {
        if (blockStatus) {
            sendPacket(C07PacketPlayerDigging(RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN))
            blockStatus = false
        }

        renderBlocking = false
    }

    /**
     * Check if run should be cancelled
     */
    private val cancelRun
        inline get() = mc.thePlayer.isSpectator || !isAlive(mc.thePlayer) || Blink.state || FreeCam.state || (noConsumeAttack == "NoRotation" && isConsumingItem())

    /**
     * Check if [entity] is alive
     */
    private fun isAlive(entity: EntityLivingBase) =
        entity.isEntityAlive && entity.health > 0 || aac && entity.hurtTime > 5

    /**
     * Check if player is able to block
     */
    private val canBlock: Boolean
        get() {
            if (currentTarget != null && mc.thePlayer?.heldItem?.item is ItemSword) {
                if (smartAutoBlock) {
                    if (!isMoving && forceBlock) return true

                    if (checkWeapon && (currentTarget!!.heldItem?.item !is ItemSword && currentTarget!!.heldItem?.item !is ItemAxe)) return false

                    if (mc.thePlayer.hurtTime > maxOwnHurtTime) return false

                    val rotationToPlayer = toRotation(getCenter(mc.thePlayer.hitBox), true, currentTarget!!)

                    if (getRotationDifference(
                            rotationToPlayer, currentTarget!!.rotation
                        ) > maxDirectionDiff
                    ) return false

                    if (currentTarget!!.swingProgressInt > maxSwingProgress) return false

                    if (currentTarget!!.getDistanceToEntityBox(mc.thePlayer) > blockRange) return false
                }


                return true
            }

            return false
        }

    /**
     * Range
     */
    private val maxRange
        get() = max(range + scanRange, throughWallsRange)

    private fun getRange(entity: Entity) =
        (if (mc.thePlayer.getDistanceToEntityBox(entity) >= throughWallsRange) range + scanRange else throughWallsRange) - if (mc.thePlayer.isSprinting) rangeSprintReduction else 0F

    /**
     * HUD Tag
     */
    override val tag
        get() = targetMode

    val isBlockingChestAura
        get() = state && target != null
}
