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

package l2server.gameserver.network.clientpackets;

import l2server.gameserver.TaskPriority;
import l2server.gameserver.model.actor.instance.Player;
import l2server.gameserver.model.actor.instance.ShuttleInstance;
import l2server.gameserver.network.serverpackets.ActionFailed;
import l2server.gameserver.network.serverpackets.ExMoveToLocationInShuttle;
import l2server.gameserver.network.serverpackets.ExStopMoveInShuttle;
import l2server.gameserver.templates.item.WeaponType;
import l2server.util.Point3D;

/**
 * @author Pere
 */
public class MoveToLocationInShuttle extends L2GameClientPacket {
	private int shuttleId;
	private int targetX;
	private int targetY;
	private int targetZ;
	private int originX;
	private int originY;
	private int originZ;
	
	public TaskPriority getPriority() {
		return TaskPriority.PR_HIGH;
	}
	
	@Override
	protected void readImpl() {
		shuttleId = readD();
		targetX = readD();
		targetY = readD();
		targetZ = readD();
		originX = readD();
		originY = readD();
		originZ = readD();
	}
	
	@Override
	protected void runImpl() {
		final Player activeChar = getClient().getActiveChar();
		if (activeChar == null) {
			return;
		}
		
		if (targetX == originX && targetY == originY && targetZ == originZ) {
			activeChar.sendPacket(new ExStopMoveInShuttle(activeChar, shuttleId));
			return;
		}
		
		if (activeChar.isAttackingNow() && activeChar.getActiveWeaponItem() != null &&
				activeChar.getActiveWeaponItem().getItemType() == WeaponType.BOW) {
			activeChar.sendPacket(ActionFailed.STATIC_PACKET);
			return;
		}
		
		if (activeChar.isSitting() || activeChar.isMovementDisabled()) {
			activeChar.sendPacket(ActionFailed.STATIC_PACKET);
			return;
		}
		
		if (!activeChar.isInShuttle()) {
			activeChar.sendPacket(ActionFailed.STATIC_PACKET);
			return;
		}
		
		final ShuttleInstance shuttle = activeChar.getShuttle();
		if (shuttle.getObjectId() != shuttleId) {
			activeChar.sendPacket(ActionFailed.STATIC_PACKET);
			return;
		}
		
		activeChar.setInVehiclePosition(new Point3D(targetX, targetY, targetZ));
		activeChar.broadcastPacket(new ExMoveToLocationInShuttle(activeChar));
	}
}
