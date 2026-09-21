package justfatlard.village_mail.gametest;

import justfatlard.pandorical.gametest.Smoke;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * This mod loads, its mixins bind, and a world comes up with it installed.
 *
 * <p>Nothing is asserted beyond getting there, and getting there proves most of it: a mixin that no
 * longer applies refuses the mod outright, a registration that throws takes the client down, and a
 * datapack that will not parse fails the world. Anything this mod can only be caught doing with a
 * player in hand belongs in the body of {@link Smoke#run}, added here when there is something
 * worth watching for.
 */
public final class SmokeTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		Smoke.run(context, "village-mail");
	}
}
