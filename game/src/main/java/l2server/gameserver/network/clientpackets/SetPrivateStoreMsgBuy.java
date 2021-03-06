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

import l2server.Config;
import l2server.gameserver.model.actor.instance.Player;
import l2server.gameserver.network.serverpackets.PrivateStoreMsgBuy;
import l2server.gameserver.util.Util;

/**
 * This class ...
 *
 * @version $Revision: 1.2.4.2 $ $Date: 2005/03/27 15:29:30 $
 */
public final class SetPrivateStoreMsgBuy extends L2GameClientPacket {
	//
	
	private static final int MAX_MSG_LENGTH = 29;
	
	private String storeMsg;
	
	@Override
	protected void readImpl() {
		storeMsg = readS();
	}
	
	@Override
	protected void runImpl() {
		final Player player = getClient().getActiveChar();
		if (player == null || player.getBuyList() == null) {
			return;
		}
		
		if (storeMsg != null && storeMsg.length() > MAX_MSG_LENGTH) {
			Util.handleIllegalPlayerAction(player,
					"Player " + player.getName() + " tried to overflow private store buy message",
					Config.DEFAULT_PUNISH);
			return;
		}
		
		player.getBuyList().setTitle(storeMsg);
		player.sendPacket(new PrivateStoreMsgBuy(player));
	}
}
