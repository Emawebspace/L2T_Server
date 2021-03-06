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

package l2server.gameserver.network.serverpackets;

import l2server.gameserver.model.PartyMatchRoom;
import l2server.gameserver.model.PartyMatchRoomList;
import l2server.gameserver.model.actor.instance.Player;

import java.util.ArrayList;

/**
 * @author Gnacik
 */
public class ListPartyWaiting extends L2GameServerPacket {
	private Player cha;
	private int loc;
	private int lim;
	private ArrayList<PartyMatchRoom> rooms;
	
	public ListPartyWaiting(Player player, int auto, int location, int limit) {
		cha = player;
		loc = location;
		lim = limit;
		rooms = new ArrayList<>();
		for (PartyMatchRoom room : PartyMatchRoomList.getInstance().getRooms()) {
			if (room.getMembers() < 1 || room.getOwner() == null || !room.getOwner().isOnline() || room.getOwner().getPartyRoom() != room.getId()) {
				PartyMatchRoomList.getInstance().deleteRoom(room.getId());
				continue;
			}
			if (loc > 0 && loc != room.getLocation()) {
				continue;
			}
			if (lim == 0 && (cha.getLevel() < room.getMinLvl() || cha.getLevel() > room.getMaxLvl())) {
				continue;
			}
			rooms.add(room);
		}
	}
	
	@Override
	protected final void writeImpl() {
		writeD(rooms.size() > 0 ? 1 : 0);
		
		writeD(rooms.size());
		for (PartyMatchRoom room : rooms) {
			writeD(room.getId());
			writeS(room.getTitle());
			writeD(room.getLocation());
			writeD(room.getMinLvl());
			writeD(room.getMaxLvl());
			writeD(room.getMaxMembers());
			writeS(room.getOwner().getName());
			writeD(room.getMembers());
			for (Player member : room.getPartyMembers()) {
				writeD(member.getClassId());
				writeS(member.getName());
			}
		}
	}
}
