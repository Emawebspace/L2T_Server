/*
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */

package l2server.gameserver.model;

import l2server.Config;
import l2server.gameserver.model.actor.Creature;
import l2server.gameserver.model.actor.Playable;
import l2server.gameserver.model.actor.Summon;
import l2server.gameserver.model.actor.instance.Player;
import l2server.gameserver.model.olympiad.OlympiadGameManager;
import l2server.gameserver.model.olympiad.OlympiadGameTask;
import l2server.gameserver.network.SystemMessageId;
import l2server.gameserver.network.serverpackets.AbnormalStatusUpdate;
import l2server.gameserver.network.serverpackets.ExOlympiadSpelledInfo;
import l2server.gameserver.network.serverpackets.PartySpelled;
import l2server.gameserver.network.serverpackets.SystemMessage;
import l2server.gameserver.templates.skills.AbnormalType;
import l2server.gameserver.templates.skills.EffectType;
import l2server.gameserver.templates.skills.SkillTargetType;
import l2server.util.Rnd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class CharEffectList {
	private static Logger log = LoggerFactory.getLogger(CharEffectList.class.getName());


	private static final Abnormal[] EMPTY_EFFECTS = new Abnormal[0];

	private AtomicBoolean queueLock = new AtomicBoolean(true);

	private CopyOnWriteArrayList<Abnormal> buffs;
	private CopyOnWriteArrayList<Abnormal> debuffs;

	// The table containing the List of all stacked effect in progress for each Stack group Identifier
	private Map<String, List<Abnormal>> stackedEffects;

	private volatile boolean hasBuffsRemovedOnAction = false;
	private volatile boolean hasBuffsRemovedOnDamage = false;
	private volatile boolean hasDebuffsRemovedOnDamage = false;
	private volatile boolean hasBuffsRemovedOnDebuffBlock = false;

	private boolean queuesInitialized = false;
	private LinkedBlockingQueue<Abnormal> addQueue;
	private LinkedBlockingQueue<Abnormal> removeQueue;
	private long effectFlags;

	// only party icons need to be updated
	private boolean partyOnly = false;

	// Owner of this list
	private Creature owner;

	public CharEffectList(Creature owner) {
		this.owner = owner;
	}

	/**
	 * Returns all effects affecting stored in this CharEffectList
	 *
	 */
	public final Abnormal[] getAllEffects() {
		// If no effect is active, return EMPTY_EFFECTS
		if ((buffs == null || buffs.isEmpty()) && (debuffs == null || debuffs.isEmpty())) {
			return EMPTY_EFFECTS;
		}

		// Create a copy of the effects
		ArrayList<Abnormal> temp = new ArrayList<>();

		// Add all buffs and all debuffs
		if (buffs != null) {
			//synchronized (buffs)
			{
				if (!buffs.isEmpty()) {
					temp.addAll(buffs);
				}
			}
		}
		if (debuffs != null) {
			//synchronized (debuffs)
			{
				if (!debuffs.isEmpty()) {
					temp.addAll(debuffs);
				}
			}
		}

		// Return all effects in an array
		Abnormal[] tempArray = new Abnormal[temp.size()];
		temp.toArray(tempArray);
		return tempArray;
	}

	public final Abnormal[] getAllDebuffs() {
		// If no effect is active, return EMPTY_EFFECTS
		if (debuffs == null || debuffs.isEmpty()) {
			return EMPTY_EFFECTS;
		}

		// Create a copy of the effects
		ArrayList<Abnormal> temp = new ArrayList<>();

		if (debuffs != null) {
			if (!debuffs.isEmpty()) {
				temp.addAll(debuffs);
			}
		}

		// Return all effects in an array
		Abnormal[] tempArray = new Abnormal[temp.size()];
		temp.toArray(tempArray);
		return tempArray;
	}

	/**
	 * Returns the first effect matching the given EffectType
	 *
	 */
	public final Abnormal getFirstEffect(AbnormalType tp) {
		Abnormal effectNotInUse = null;

		if (buffs != null) {
			//synchronized (buffs)
			{
				if (!buffs.isEmpty()) {
					for (Abnormal e : buffs) {
						if (e == null) {
							continue;
						}

						if (e.getType() == tp) {
							if (e.getInUse()) {
								return e;
							} else {
								effectNotInUse = e;
							}
						}
					}
				}
			}
		}
		if (effectNotInUse == null && debuffs != null) {
			//synchronized (debuffs)
			{
				if (!debuffs.isEmpty()) {
					for (Abnormal e : debuffs) {
						if (e == null) {
							continue;
						}
						if (e.getType() == tp) {
							if (e.getInUse()) {
								return e;
							} else {
								effectNotInUse = e;
							}
						}
					}
				}
			}
		}
		return effectNotInUse;
	}

	/**
	 * Returns the first effect matching the given Skill
	 *
	 */
	public final Abnormal getFirstEffect(Skill skill) {
		Abnormal effectNotInUse = null;

		if (skill.isDebuff()) {
			if (debuffs == null) {
				return null;
			}

			//synchronized (debuffs)
			{
				if (debuffs.isEmpty()) {
					return null;
				}

				for (Abnormal e : debuffs) {
					if (e == null) {
						continue;
					}
					if (e.getSkill() == skill) {
						if (e.getInUse()) {
							return e;
						} else {
							effectNotInUse = e;
						}
					}
				}
			}
			return effectNotInUse;
		} else {
			if (buffs == null) {
				return null;
			}

			//synchronized (buffs)
			{
				if (buffs.isEmpty()) {
					return null;
				}

				for (Abnormal e : buffs) {
					if (e == null) {
						continue;
					}
					if (e.getSkill() == skill) {
						if (e.getInUse()) {
							return e;
						} else {
							effectNotInUse = e;
						}
					}
				}
			}
			return effectNotInUse;
		}
	}

	/**
	 * Returns the first effect matching the given skillId
	 *
	 */
	public final Abnormal getFirstEffect(int skillId) {
		Abnormal effectNotInUse = null;

		if (buffs != null) {
			//synchronized (buffs)
			{
				if (!buffs.isEmpty()) {
					for (Abnormal e : buffs) {
						if (e == null) {
							continue;
						}
						if (e.getSkill().getId() == skillId) {
							if (e.getInUse()) {
								return e;
							} else {
								effectNotInUse = e;
							}
						}
					}
				}
			}
		}

		if (effectNotInUse == null && debuffs != null) {
			//synchronized (debuffs)
			{
				if (!debuffs.isEmpty()) {
					for (Abnormal e : debuffs) {
						if (e == null) {
							continue;
						}
						if (e.getSkill().getId() == skillId) {
							if (e.getInUse()) {
								return e;
							} else {
								effectNotInUse = e;
							}
						}
					}
				}
			}
		}

		return effectNotInUse;
	}

	public final Abnormal getFirstEffectByName(String effectName) {
		Abnormal effectNotInUse = null;
		if (buffs != null) {
			//synchronized (buffs)
			{
				if (!buffs.isEmpty()) {
					for (Abnormal e : buffs) {
						if (e == null) {
							continue;
						}

						for (L2Effect eff : e.getEffects()) {
							if (eff == null) {
								continue;
							}

							if (eff.getTemplate().funcName.equalsIgnoreCase(effectName)) {
								if (e.getInUse()) {
									return e;
								} else {
									effectNotInUse = e;
								}
							}
						}
					}
				}
			}
		}

		if (effectNotInUse == null && debuffs != null) {
			//synchronized (debuffs)
			{
				if (!debuffs.isEmpty()) {
					for (Abnormal e : debuffs) {
						if (e == null) {
							continue;
						}

						for (L2Effect eff : e.getEffects()) {
							if (eff == null) {
								continue;
							}

							if (eff.getTemplate().funcName.equalsIgnoreCase(effectName)) {
								if (e.getInUse()) {
									return e;
								} else {
									effectNotInUse = e;
								}
							}
						}
					}
				}
			}
		}
		return effectNotInUse;
	}

	/**
	 * Returns the first effect matching the given skillId
	 *
	 */
	public final Abnormal getFirstEffect(final String stackType) {
		Abnormal effectNotInUse = null;

		if (buffs != null) {
			//synchronized (buffs)
			{
				if (!buffs.isEmpty()) {
					for (Abnormal e : buffs) {
						if (e == null) {
							continue;
						}
						if (e.getSkill().getFirstEffectStack().equals(stackType)) {
							if (e.getInUse()) {
								return e;
							} else {
								effectNotInUse = e;
							}
						}
					}
				}
			}
		}

		if (effectNotInUse == null && debuffs != null) {
			//synchronized (debuffs)
			{
				if (!debuffs.isEmpty()) {
					for (Abnormal e : debuffs) {
						if (e == null) {
							continue;
						}
						if (e.getSkill().getFirstEffectStack().equals(stackType)) {
							if (e.getInUse()) {
								return e;
							} else {
								effectNotInUse = e;
							}
						}
					}
				}
			}
		}

		return effectNotInUse;
	}

	/**
	 * Return the number of buffs in this CharEffectList not counting Songs/Dances
	 *
	 */
	public int getBuffCount() {
		if (buffs == null) {
			return 0;
		}
		int buffCount = 0;

		//synchronized(buffs)
		{
			if (buffs.isEmpty()) {
				return 0;
			}

			for (Abnormal e : buffs) {
				if (e != null && e.getShowIcon() && !e.getSkill().isDance() && !e.getSkill().isToggle() && !e.getSkill().isActivation() &&
						!e.getSkill().is7Signs()) {
					switch (e.getSkill().getSkillType()) {
						case BUFF:
						case HEAL_PERCENT:
						case MANAHEAL_PERCENT:
							buffCount++;
					}
				}
			}
		}
		return buffCount;
	}

	/**
	 * Return the number of Songs/Dances in this CharEffectList
	 *
	 */
	public int getDanceCount() {
		if (buffs == null) {
			return 0;
		}
		int danceCount = 0;

		//synchronized(buffs)
		{
			if (buffs.isEmpty()) {
				return 0;
			}

			for (Abnormal e : buffs) {
				if (e != null && e.getSkill().isDance() && e.getInUse()) {
					danceCount++;
				}
			}
		}
		return danceCount;
	}

	/**
	 * Return the number of Activation buffs in this CharEffectList
	 *
	 */
	public int getActivationCount() {
		if (buffs == null) {
			return 0;
		}
		int danceCount = 0;

		//synchronized(buffs)
		{
			if (buffs.isEmpty()) {
				return 0;
			}

			for (Abnormal e : buffs) {
				if (e != null && e.getSkill().isActivation() && e.getInUse()) {
					danceCount++;
				}
			}
		}
		return danceCount;
	}

	/**
	 * Exits all effects in this CharEffectList
	 */
	public final void stopAllEffects() {
		// Get all active skills effects from this list
		Abnormal[] effects = getAllEffects();

		// Exit them
		for (Abnormal e : effects) {
			if (e != null) {
				e.exit(true);
			}
		}
	}

	/**
	 * Exits all effects in this CharEffectList
	 */
	public final void stopAllEffectsExceptThoseThatLastThroughDeath() {
		// Get all active skills effects from this list
		Abnormal[] effects = getAllEffects();

		// Exit them
		for (Abnormal e : effects) {
			if (e != null && !e.getSkill().isStayAfterDeath()) {
				e.exit(true);
			}
		}
	}

	/**
	 * Exit all toggle-type effects
	 */
	public void stopAllToggles() {
		if (buffs != null) {
			//synchronized (buffs)
			{
				if (!buffs.isEmpty()) {
					buffs.stream().filter(e -> e != null && e.getSkill().isToggle()).forEachOrdered(Abnormal::exit);
				}
			}
		}
	}

	/**
	 * Exit all effects having a specified type
	 *
	 */
	public final void stopEffects(AbnormalType type) {
		// Go through all active skills effects
		ArrayList<Abnormal> temp = new ArrayList<>();
		if (buffs != null) {
			//synchronized (buffs)
			{
				if (!buffs.isEmpty()) {
					temp.addAll(buffs.stream().filter(e -> e != null && e.getType() == type).collect(Collectors.toList()));
				}
			}
		}
		if (debuffs != null) {
			//synchronized (debuffs)
			{
				if (!debuffs.isEmpty()) {
					temp.addAll(debuffs.stream().filter(e -> e != null && e.getType() == type).collect(Collectors.toList()));
				}
			}
		}
		if (!temp.isEmpty()) {
			temp.stream().filter(e -> e != null).forEachOrdered(Abnormal::exit);
		}
	}

	/**
	 * Exit all effects having a specified type
	 *
	 */
	public final void stopEffects(EffectType type) {
		// Go through all active skills effects
		ArrayList<Abnormal> temp = new ArrayList<>();
		if (buffs != null) {
			//synchronized (buffs)
			{
				if (!buffs.isEmpty()) {
					temp.addAll(buffs.stream().filter(e -> e != null && (e.getEffectMask() & type.getMask()) > 0).collect(Collectors.toList()));
				}
			}
		}
		if (debuffs != null) {
			//synchronized (debuffs)
			{
				if (!debuffs.isEmpty()) {
					temp.addAll(debuffs.stream().filter(e -> e != null && (e.getEffectMask() & type.getMask()) > 0).collect(Collectors.toList()));
				}
			}
		}
		if (!temp.isEmpty()) {
			temp.stream().filter(e -> e != null).forEachOrdered(Abnormal::exit);
		}
	}

	/**
	 * Exits all effects created by a specific skillId
	 *
	 */
	public final void stopSkillEffects(int skillId) {
		// Go through all active skills effects
		ArrayList<Abnormal> temp = new ArrayList<>();
		if (buffs != null) {
			//synchronized (buffs)
			{
				if (!buffs.isEmpty()) {
					temp.addAll(buffs.stream().filter(e -> e != null && e.getSkill().getId() == skillId).collect(Collectors.toList()));
				}
			}
		}
		if (debuffs != null) {
			//synchronized (debuffs)
			{
				if (!debuffs.isEmpty()) {
					temp.addAll(debuffs.stream().filter(e -> e != null && e.getSkill().getId() == skillId).collect(Collectors.toList()));
				}
			}
		}
		if (!temp.isEmpty()) {
			temp.stream().filter(e -> e != null).forEachOrdered(Abnormal::exit);
		}
	}

	/**
	 * Exits all buffs effects of the skills with "removedOnAnyAction" set.
	 * Called on any action except movement (attack, cast).
	 */
	public void stopEffectsOnAction(Skill skill) {
		boolean friendlyAction = skill != null &&
				(skill.getTargetType() == SkillTargetType.TARGET_SELF || skill.getTargetType() == SkillTargetType.TARGET_FRIENDS);
		if (hasBuffsRemovedOnAction && buffs != null && !buffs.isEmpty()) {
			for (Abnormal e : buffs) {
				if (e == null || !e.getSkill().isRemovedOnAction() || friendlyAction) {
					continue;
				}

				e.exit(true);
			}
		}
	}

	public void stopEffectsOnDamage(boolean awake, int damage) {
		if (hasBuffsRemovedOnDamage && buffs != null && !buffs.isEmpty()) {
			for (Abnormal e : buffs) {
				if (e != null && awake) {
					if (e.getType() == AbnormalType.FEAR || e.getType() == AbnormalType.SLEEP) {
						continue;
					}

					if (e.isRemovedOnDamage(damage)) {
						e.exit(true);
					}
				}
			}
		}

		if (hasDebuffsRemovedOnDamage && debuffs != null && !debuffs.isEmpty()) {
			for (Abnormal e : debuffs) {
				if (e != null && awake) {
					if (e.isRemovedOnDamage(damage)) {
						if (e.getSkill().getRemovedOnDamageChance() != 0) {
							if (e.getSkill().getRemovedOnDamageChance() >= Rnd.get(100)) {
								e.exit(true);
							}
							return;
						}
						e.exit(true);
					}
				}
			}
		}
	}

	public void stopEffectsOnDebuffBlock() {
		if (hasBuffsRemovedOnDebuffBlock && buffs != null && !buffs.isEmpty()) {
			buffs.stream().filter(e -> e != null).filter(e -> e.isRemovedOnDebuffBlock(true)).forEachOrdered(e -> {
				e.exit(true);
			});
		}
	}

	public void updateEffectIcons(boolean partyOnly) {
		if (buffs == null && debuffs == null) {
			return;
		}

		if (partyOnly) {
			partyOnly = true;
		}

		queueRunner();
	}

	public void queueEffect(Abnormal effect, boolean remove) {
		if (effect == null) {
			return;
		}

		if (!queuesInitialized) {
			init();
		}

		if (remove) {
			removeQueue.offer(effect);
		} else {
			addQueue.offer(effect);
		}

		queueRunner();
	}

	private synchronized void init() {
		if (queuesInitialized) {
			return;
		}

		addQueue = new LinkedBlockingQueue<>();
		removeQueue = new LinkedBlockingQueue<>();
		queuesInitialized = true;
	}

	private void queueRunner() {
		if (!queueLock.compareAndSet(true, false)) {
			return;
		}

		try {
			Abnormal effect;
			do {
				// remove has more priority than add
				// so removing all effects from queue first
				while ((effect = removeQueue.poll()) != null) {
					removeEffectFromQueue(effect);
					partyOnly = false;
				}

				if ((effect = addQueue.poll()) != null) {
					addEffectFromQueue(effect);
					partyOnly = false;
				}
			} while (!addQueue.isEmpty() || !removeQueue.isEmpty());

			computeEffectFlags();
			updateEffectIcons();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			queueLock.set(true);
		}
	}

	protected void removeEffectFromQueue(Abnormal effect) {
		if (effect == null) {
			return;
		}

		CopyOnWriteArrayList<Abnormal> effectList;

		if (effect.getSkill().isDebuff()) {
			if (debuffs == null) {
				return;
			}
			effectList = debuffs;
		} else {
			if (buffs == null) {
				return;
			}
			effectList = buffs;
		}

		if (effect.getStackType().length == 0) {
			// Remove Func added by this effect from the Creature Calculator
			owner.removeStatsOwner(effect);
		} else {
			if (stackedEffects == null) {
				return;
			}

			for (String stackType : effect.getStackType()) {
				// Get the list of all stacked effects corresponding to the stack type of the L2Effect to add
				List<Abnormal> stackQueue = stackedEffects.get(stackType);

				if (stackQueue == null || stackQueue.isEmpty()) {
					continue;
				}

				int index = stackQueue.indexOf(effect);

				// Remove the effect from the stack group
				if (index >= 0) {
					stackQueue.remove(effect);
					// Check if the first stacked effect was the effect to remove
					if (index == 0) {
						// Remove all its Func objects from the Creature calculator set
						owner.removeStatsOwner(effect);

						// Check if there's another effect in the Stack Group
						if (!stackQueue.isEmpty()) {
							Abnormal newStackedEffect = listsContains(stackQueue.get(0));
							if (newStackedEffect != null) {
								// Set the effect to In Use
								if (newStackedEffect.setInUse(true))
								// Add its list of Funcs to the Calculator set of the Creature
								{
									owner.addStatFuncs(newStackedEffect.getStatFuncs());
								}
							}
						}
					}
					if (stackQueue.isEmpty()) {
						stackedEffects.remove(stackType);
					} else {
						// Update the Stack Group table stackedEffects of the Creature
						stackedEffects.put(stackType, stackQueue);
					}
				}
			}
		}

		// Remove the active skill L2effect from effects of the Creature
		boolean removed = effectList.remove(effect);
		if (removed && owner instanceof Player && effect.getShowIcon()) {
			SystemMessage sm;
			if (effect.getSkill().isToggle()) {
				sm = SystemMessage.getSystemMessage(SystemMessageId.S1_HAS_BEEN_ABORTED);
			} else {
				sm = SystemMessage.getSystemMessage(SystemMessageId.EFFECT_S1_DISAPPEARED);
			}
			sm.addSkillName(effect);
			owner.sendPacket(sm);
		}
	}

	protected void addEffectFromQueue(Abnormal newEffect) {
		if (newEffect == null) {
			return;
		}

		Skill newSkill = newEffect.getSkill();

		if (newSkill.isDebuff()) {
			if (debuffs == null) {
				debuffs = new CopyOnWriteArrayList<>();
			}

			for (Abnormal e : debuffs) {
				if (e != null && e.getSkill().getId() == newEffect.getSkill().getId() && e.getType() == newEffect.getType() &&
						e.getStackLvl() == newEffect.getStackLvl() && Arrays.equals(e.getStackType(), newEffect.getStackType())) {
					// Started scheduled timer needs to be canceled.
					if (newEffect.getDuration() - newEffect.getTime() < e.getDuration() - e.getTime()) {
						newEffect.stopEffectTask();
						return;
					} else {
						e.stopEffectTask();
					}
				}
			}
			debuffs.add(newEffect);
		} else {
			if (buffs == null) {
				buffs = new CopyOnWriteArrayList<>();
			}

			for (Abnormal e : buffs) {
				if (e != null && e.getSkill().getId() == newEffect.getSkill().getId() && e.getType() == newEffect.getType() &&
						e.getStackLvl() == newEffect.getStackLvl()) {
					boolean sameStackType = e.getStackType().length == newEffect.getStackType().length;
					if (sameStackType) {
						for (int i = 0; i < e.getStackType().length; i++) {
							if (!e.getStackType()[i].equals(newEffect.getStackType()[i])) {
								sameStackType = false;
								break;
							}
						}
					}

					if (sameStackType) {
						e.exit(); // exit this
					}
				}
			}

			// if max buffs, no herb effects are used, even if they would replace one old
			if (newEffect.isHerbEffect() && getBuffCount() >= owner.getMaxBuffCount()) {
				newEffect.stopEffectTask();
				return;
			}

			// Remove first buff when buff list is full
			int effectsToRemove;
			if (newSkill.isActivation()) {
				effectsToRemove = getActivationCount() - 24;
				if (effectsToRemove >= 0) {
					for (Abnormal e : buffs) {
						if (e == null || !e.getSkill().isActivation()) {
							continue;
						}

						// get first dance
						e.exit();
						effectsToRemove--;
						if (effectsToRemove < 0) {
							break;
						}
					}
				}
			} else if (newSkill.isDance()) {
				effectsToRemove = getDanceCount() - Config.DANCES_MAX_AMOUNT;
				if (effectsToRemove >= 0) {
					for (Abnormal e : buffs) {
						if (e == null || !e.getSkill().isDance()) {
							continue;
						}

						// get first dance
						e.exit();
						effectsToRemove--;
						if (effectsToRemove < 0) {
							break;
						}
					}
				}
			} else if (!newSkill.isToggle()) {
				effectsToRemove = getBuffCount() - owner.getMaxBuffCount();
				if (effectsToRemove >= 0) {
					switch (newSkill.getSkillType()) {
						case BUFF:
						case HEAL_PERCENT:
						case MANAHEAL_PERCENT:
							for (Abnormal e : buffs) {
								if (e == null || e.getSkill().isDance()) {
									continue;
								}

								switch (e.getSkill().getSkillType()) {
									case BUFF:
									case HEAL_PERCENT:
									case MANAHEAL_PERCENT:
										e.exit();
										effectsToRemove--;
										break; // break switch()
									default:
										continue; // continue for ()
								}
								if (effectsToRemove < 0) {
									break; // break for ()
								}
							}
					}
				}
			}

			// Icons order: buffs, 7s, toggles, dances, activation
			if (newSkill.isActivation()) {
				buffs.add(newEffect);
			} else {
				int pos = 0;
				if (newSkill.isDance()) {
					// toggle skill - before all dances
					for (Abnormal e : buffs) {
						if (e == null) {
							continue;
						}
						if (e.getSkill().isActivation()) {
							break;
						}
						pos++;
					}
				} else if (newSkill.isToggle()) {
					// toggle skill - before all dances
					for (Abnormal e : buffs) {
						if (e == null) {
							continue;
						}
						if (e.getSkill().isDance() || e.getSkill().isActivation()) {
							break;
						}
						pos++;
					}
				} else {
					// normal buff - before toggles and 7s and dances
					for (Abnormal e : buffs) {
						if (e == null) {
							continue;
						}
						if (e.getSkill().isToggle() || e.getSkill().is7Signs() || e.getSkill().isDance() || e.getSkill().isActivation()) {
							break;
						}
						pos++;
					}
				}
				buffs.add(pos, newEffect);
			}
		}

		// Check if a stack group is defined for this effect
		if (newEffect.getStackType().length == 0) {
			// Set this L2Effect to In Use
			if (newEffect.setInUse(true))
			// Add Funcs of this effect to the Calculator set of the Creature
			{
				owner.addStatFuncs(newEffect.getStatFuncs());
			} else {
				if (newEffect.getSkill().isDebuff()) {
					debuffs.remove(newEffect);
				} else {
					buffs.remove(newEffect);
				}
			}

			return;
		}

		if (stackedEffects == null) {
			stackedEffects = new HashMap<>();
		}

		Set<Abnormal> effectsToAdd = new HashSet<>();
		Set<Abnormal> effectsToRemove = new HashSet<>();
		Set<Abnormal> removed = new HashSet<>();
		for (String stackType : newEffect.getStackType()) {
			Abnormal effectToAdd = null;
			Abnormal effectToRemove = null;
			// Get the list of all stacked effects corresponding to the stack type of the L2Effect to add
			List<Abnormal> stackQueue = stackedEffects.get(stackType);
			if (stackQueue != null) {
				int pos = 0;
				if (!stackQueue.isEmpty()) {
					// Get the first stacked effect of the Stack group selected
					effectToRemove = listsContains(stackQueue.get(0));

					// Create an Iterator to go through the list of stacked effects in progress on the Creature
					Iterator<Abnormal> queueIterator = stackQueue.iterator();

					while (queueIterator.hasNext()) {
						if (newEffect.getStackLvl() < queueIterator.next().getStackLvl()) {
							pos++;
						} else {
							break;
						}
					}
					// Add the new effect to the Stack list in function of its position in the Stack group
					stackQueue.add(pos, newEffect);

					// skill.exit() could be used, if the users don't wish to see "effect
					// removed" always when a timer goes off, even if the buff isn't active
					// any more (has been replaced). but then check e.g. npc hold and raid petrification.
					if (Config.EFFECT_CANCELING && !newEffect.isHerbEffect() && stackQueue.size() > 1) {
						Abnormal toRemove = stackQueue.remove(1);
						if (!removed.contains(toRemove)) {
							removed.add(toRemove);
							if (newSkill.isDebuff()) {
								debuffs.remove(toRemove);
							} else {
								buffs.remove(toRemove);
							}
							toRemove.exit();
						}
					}
				} else {
					stackQueue.add(0, newEffect);
				}
			} else {
				stackQueue = new ArrayList<>();
				stackQueue.add(0, newEffect);
			}

			// Update the Stack Group table stackedEffects of the Creature
			stackedEffects.put(stackType, stackQueue);

			// Get the first stacked effect of the Stack group selected
			if (!stackQueue.isEmpty()) {
				effectToAdd = listsContains(stackQueue.get(0));
			}

			if (effectToRemove != effectToAdd) {
				if (effectToRemove != null) {
					effectsToRemove.add(effectToRemove);
				}
				if (effectToAdd != null) {
					effectsToAdd.add(effectToAdd);
				}
			}
		}

		for (Abnormal a : effectsToRemove) {
			// Set the L2Effect to Not In Use
			a.setInUse(false);
		}

		for (Abnormal a : effectsToAdd) {
			// To be added it must be first in all its stack types
			boolean firstInAll = true;
			for (String stackType : a.getStackType()) {
				if (stackedEffects.get(stackType).get(0) != a) {
					firstInAll = false;
					break;
				}
			}

			if (firstInAll) {
				// Set this L2Effect to In Use
				if (a.setInUse(true))
				// Add all Func objects corresponding to this stacked effect to the Calculator set of the Creature
				{
					owner.addStatFuncs(a.getStatFuncs());
				} else {
					if (a.getSkill().isDebuff()) {
						debuffs.remove(a);
					} else {
						buffs.remove(a);
					}
				}
			} else {
				// Remove it from the stack
				for (String stackType : a.getStackType()) {
					stackedEffects.get(stackType).remove(a);
				}
			}
		}

		for (Abnormal a : effectsToRemove) {
			// Remove all Func objects corresponding to this stacked effect from the Calculator set of the Creature
			owner.removeStatsOwner(a);
		}
	}

	protected void updateEffectIcons() {
		if (owner == null) {
			return;
		}

		owner.broadcastAbnormalStatusUpdate();

		if (!(owner instanceof Playable)) {
			updateEffectFlags();
			return;
		}

		AbnormalStatusUpdate mi = null;
		PartySpelled ps = null;
		ExOlympiadSpelledInfo os = null;

		if (owner instanceof Player) {
			if (partyOnly) {
				partyOnly = false;
			} else {
				mi = new AbnormalStatusUpdate();
			}

			if (owner.isInParty()) {
				ps = new PartySpelled(owner);
			}

			if (((Player) owner).isInOlympiadMode() && ((Player) owner).isOlympiadStart()) {
				os = new ExOlympiadSpelledInfo((Player) owner);
			}
		} else if (owner instanceof Summon) {
			ps = new PartySpelled(owner);
		}

		boolean foundRemovedOnAction = false;
		boolean foundRemovedOnDamage = false;
		boolean foundRemovedOnDebuffBlock = false;

		if (buffs != null && !buffs.isEmpty()) {
			//synchronized (buffs)
			{
				for (Abnormal e : buffs) {
					if (e == null) {
						continue;
					}

					if (e.getSkill().isRemovedOnAction()) {
						foundRemovedOnAction = true;
					}
					if (e.isRemovedOnDamage(0)) {
						foundRemovedOnDamage = true;
					}
					if (e.isRemovedOnDebuffBlock(false)) {
						foundRemovedOnDebuffBlock = true;
					}

					if (!e.getShowIcon()) {
						continue;
					}

					switch (e.getType()) {
						case CHARGE: // handled by EtcStatusUpdate
						case SIGNET_GROUND:
							continue;
					}

					if (e.getInUse()) {
						if (mi != null) {
							e.addIcon(mi);
						}

						if (ps != null) {
							e.addPartySpelledIcon(ps);
						}

						if (os != null) {
							e.addOlympiadSpelledIcon(os);
						}
					}
				}
			}
		}

		hasBuffsRemovedOnAction = foundRemovedOnAction;
		hasBuffsRemovedOnDamage = foundRemovedOnDamage;
		hasBuffsRemovedOnDebuffBlock = foundRemovedOnDebuffBlock;
		foundRemovedOnDamage = false;

		if (debuffs != null && !debuffs.isEmpty()) {
			//synchronized (debuffs)
			{
				for (Abnormal e : debuffs) {
					if (e == null) {
						continue;
					}

					if (e.isRemovedOnDamage(0)) {
						foundRemovedOnDamage = true;
					}

					if (!e.getShowIcon()) {
						continue;
					}

					switch (e.getType()) {
						case SIGNET_GROUND:
							continue;
					}

					if (e.getInUse()) {
						if (mi != null) {
							e.addIcon(mi);
						}

						if (ps != null) {
							e.addPartySpelledIcon(ps);
						}

						if (os != null) {
							e.addOlympiadSpelledIcon(os);
						}
					}
				}
			}
		}

		hasDebuffsRemovedOnDamage = foundRemovedOnDamage;

		if (mi != null) {
			owner.sendPacket(mi);
		}

		if (ps != null) {
			if (owner instanceof Summon) {
				Player summonOwner = ((Summon) owner).getOwner();

				if (summonOwner != null) {
					if (summonOwner.isInParty()) {
						summonOwner.getParty().broadcastToPartyMembers(ps);
					} else {
						summonOwner.sendPacket(ps);
					}
				}
			} else if (owner instanceof Player && owner.isInParty()) {
				owner.getParty().broadcastToPartyMembers(ps);
			}
		}

		if (os != null) {
			final OlympiadGameTask game = OlympiadGameManager.getInstance().getOlympiadTask(((Player) owner).getOlympiadGameId());
			if (game != null && game.isBattleStarted()) {
				game.getZone().broadcastPacketToObservers(os, game.getGame().getGameId());
			}
		}
	}

	protected void updateEffectFlags() {
		boolean foundRemovedOnAction = false;
		boolean foundRemovedOnDamage = false;
		boolean foundRemovedOnDebuffBlock = false;

		if (buffs != null && !buffs.isEmpty()) {
			//synchronized (buffs)
			{
				for (Abnormal e : buffs) {
					if (e == null) {
						continue;
					}

					if (e.getSkill().isRemovedOnAction()) {
						foundRemovedOnAction = true;
					}
					if (e.isRemovedOnDamage(0)) {
						foundRemovedOnDamage = true;
					}
					if (e.isRemovedOnDebuffBlock(false)) {
						foundRemovedOnDebuffBlock = true;
					}
				}
			}
		}
		hasBuffsRemovedOnAction = foundRemovedOnAction;
		hasBuffsRemovedOnDamage = foundRemovedOnDamage;
		hasBuffsRemovedOnDebuffBlock = foundRemovedOnDebuffBlock;
		foundRemovedOnDamage = false;

		if (debuffs != null && !debuffs.isEmpty()) {
			//synchronized (debuffs)
			{
				for (Abnormal e : debuffs) {
					if (e == null) {
						continue;
					}

					if (e.isRemovedOnDamage(0)) {
						foundRemovedOnDamage = true;
					}
				}
			}
		}
		hasDebuffsRemovedOnDamage = foundRemovedOnDamage;
	}

	/**
	 * Returns effect if contains in buffs or debuffs and null if not found
	 *
	 */
	private Abnormal listsContains(Abnormal effect) {
		if (buffs != null && !buffs.isEmpty() && buffs.contains(effect)) {
			return effect;
		}
		if (debuffs != null && !debuffs.isEmpty() && debuffs.contains(effect)) {
			return effect;
		}
		return null;
	}

	/**
	 * Recalculate effect bits flag.<br>
	 * Please no concurrency access
	 */
	private void computeEffectFlags() {
		int flags = 0;

		if (buffs != null) {
			for (Abnormal e : buffs) {
				if (e == null) {
					continue;
				}
				flags |= e.getEffectMask();
			}
		}

		if (debuffs != null) {
			for (Abnormal e : debuffs) {
				if (e == null) {
					continue;
				}
				flags |= e.getEffectMask();
			}
		}

		effectFlags = flags;
	}

	/**
	 * Check if target is affected with special buff
	 *
	 * @param bitFlag flag of special buff
	 * @return boolean true if affected
	 */
	public boolean isAffected(long bitFlag) {
		return (effectFlags & bitFlag) != 0;
	}

	/**
	 * Clear and null all queues and lists
	 * Use only during delete character from the world.
	 */
	public void clear() {
		try {
			if (addQueue != null) {
				addQueue.clear();
				addQueue = null;
			}
			if (removeQueue != null) {
				removeQueue.clear();
				removeQueue = null;
			}
			queuesInitialized = false;

			if (buffs != null) {
				buffs.clear();
				buffs = null;
			}
			if (debuffs != null) {
				debuffs.clear();
				debuffs = null;
			}

			if (stackedEffects != null) {
				stackedEffects.clear();
				stackedEffects = null;
			}
		} catch (Exception e) {
			log.warn("", e);
		}
	}
}
