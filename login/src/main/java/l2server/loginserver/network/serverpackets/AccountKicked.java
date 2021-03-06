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

package l2server.loginserver.network.serverpackets;

/**
 * @author KenM
 */
public final class AccountKicked extends L2LoginServerPacket {
	public enum AccountKickedReason {
		REASON_DATA_STEALER(0x01),
		REASON_GENERIC_VIOLATION(0x08),
		REASON_7_DAYS_SUSPENDED(0x10),
		REASON_PERMANENTLY_BANNED(0x20);
		
		private final int code;
		
		AccountKickedReason(int code) {
			this.code = code;
		}
		
		public final int getCode() {
			return code;
		}
	}
	
	private AccountKickedReason reason;
	
	public AccountKicked(AccountKickedReason reason) {
		this.reason = reason;
	}
	
	@Override
	protected void write() {
		writeC(0x02);
		writeD(reason.getCode());
	}
}
