package oldBots.fakeStructures;

import aic2024.user.*;

import java.util.ArrayList;

public class Astronaught {
    Direction[] directions = Direction.values();
    boolean bugNaving = false;
    boolean followingWall=false;

    boolean turningRight;

    ArrayList<Location> structures=new ArrayList<>();

    int closestYet = 10000;

    boolean switched;
    AstronautInfo[] attack;

    Direction dir;

    double[][] distanceGraph= new double[13][13];
    public Astronaught(UnitController uc) {
        int dirIndex = (int) (uc.getRandomDouble() * 8.0);
        Direction randomDir = directions[dirIndex];

        for (int i = 0; i < 13; i++) {
            for (int j = 0; j < 13; j++) {
                distanceGraph[i][j]=Double.POSITIVE_INFINITY;
            }
        }
        distanceGraph[6][6]=0;

        Location randomLoc=new Location((int)(uc.getRandomDouble()* uc.getMapWidth()),(int)(uc.getRandomDouble()*uc.getMapHeight()));

        while (true) {

            boolean switched=false;

            BroadcastInfo message = uc.pollBroadcast();
            while (message != null) {
                Location location=new Location(message.getMessage()%(int)Math.pow(2,12)%GameConstants.MAX_MAP_SIZE,(message.getMessage()%(int)Math.pow(2,12))/GameConstants.MAX_MAP_SIZE);

                if (message.getMessage()/(int)Math.pow(2,12)==1) {
                    structures.remove(location);
                }
                else {
                    if (!structures.contains(location)) {
                        structures.add(location);
                    }
                }
                message = uc.pollBroadcast();
            }

            attackThenCollect(uc);
            if (uc.canSenseLocation(randomLoc)) {
                bugNaving=false;
                randomLoc=new Location((int)(uc.getRandomDouble()* uc.getMapWidth()),(int)(uc.getRandomDouble()*uc.getMapHeight()));
            }

            updateDistanceGraph(uc);

            getAttack(uc);

            //move randomly, turning right if we can't move.

            if (uc.canPerformAction(ActionType.MOVE,Direction.ZERO,0)) {

                Location closest=graphClosest(uc,uc.senseStructures(GameConstants.ASTRONAUT_VISION_RANGE,uc.getOpponent()));
                //need to implement micro, which astros to move towards
                //closest=graphClosest(uc,uc.senseAstronauts(GameConstants.ASTRONAUT_VISION_RANGE,uc.getOpponent()));
                //moveTo(uc,closest);
                if (closest==null) {
                    closest = graphClosest(uc, toPickUp(uc.senseCarePackages(GameConstants.ASTRONAUT_VISION_RANGE)));
                }
                if (closest==null) {
                    closest=graphClosest(uc, attack);
                }
                if (closest==null) {
                    uc.drawPointDebug(randomLoc,100,0,0);
                    bugNav(uc,randomLoc);
                }
                else {
                    moveTo(uc,closest);
                }
            }

            broadcastOpponentStructures(uc);
            attackThenCollect(uc);

            //If we have 1 or 2 oxygen left, terraform my tile (alternatively, terraform a random tile)
            if (uc.getAstronautInfo().getOxygen() <= 1) {
                if (uc.canPerformAction(ActionType.TERRAFORM, Direction.ZERO, 0)) {
                    uc.performAction(ActionType.TERRAFORM, Direction.ZERO, 0);
                }
                else {
                    dirIndex = (int) (uc.getRandomDouble() * 8.0);
                    randomDir = directions[dirIndex];
                    for (int i = 0; i < 8; ++i) {
                        //Note that the 'value' of the following command is irrelevant.
                        if (uc.canPerformAction(ActionType.TERRAFORM, randomDir, 0)) {
                            uc.performAction(ActionType.TERRAFORM, randomDir, 0);
                            break;
                        }
                        randomDir = randomDir.rotateRight();
                    }
                }
            }
            uc.yield();
        }
    }
    public double maxOxygen(AstronautInfo[] astronautInfos) {
        double max=0;
        for (AstronautInfo astronaut: astronautInfos) {
            max = Math.max(max,astronaut.getOxygen());
        }
        return max;
    }
    public void attackThenCollect(UnitController uc) {
        for (StructureInfo structure:uc.senseStructures(2,uc.getOpponent())) {
            if (uc.canPerformAction(ActionType.SABOTAGE, uc.getLocation().directionTo(structure.getLocation()), 0)) {
                uc.performAction(ActionType.SABOTAGE, uc.getLocation().directionTo(structure.getLocation()), 0);
            }
        }

        double maxOxygen=maxOxygen(uc.senseAstronauts(2,uc.getOpponent()));
        if (uc.senseAstronauts(2,uc.getOpponent()).length>0 && maxOxygen>uc.getAstronautInfo().getOxygen()) {
            for (AstronautInfo astronautOpponent: uc.senseAstronauts(2,uc.getOpponent())) {
                if (uc.canPerformAction(ActionType.SABOTAGE,uc.getLocation().directionTo(astronautOpponent.getLocation()),0) && astronautOpponent.getOxygen()==maxOxygen) {
                    uc.performAction(ActionType.SABOTAGE,uc.getLocation().directionTo(astronautOpponent.getLocation()),0);
                }
            }
        }

        //Check if there are Care Packages at an adjacent tile. If so, retrieve them.
        for (Direction dir : directions) {
            Location adjLocation = uc.getLocation().add(dir);
            if (!uc.canSenseLocation(adjLocation)) continue;
            CarePackage cp = uc.senseCarePackage(adjLocation);
            if (cp==CarePackage.PLANTS || cp==CarePackage.OXYGEN_TANK || cp==CarePackage.SURVIVAL_KIT || cp==CarePackage.REINFORCED_SUIT) {
                if (uc.canPerformAction(ActionType.RETRIEVE, dir, 0)) {
                    uc.performAction(ActionType.RETRIEVE, dir, 0);
                    break;
                }
            }
        }
        //transfer oxygen to reinforced suits
        for (Direction dir : directions) {
            Location adjLocation = uc.getLocation().add(dir);
            if (!uc.canSenseLocation(adjLocation)) continue;
            AstronautInfo suited = uc.senseAstronaut(adjLocation);
            if (suited!=null && suited.getCarePackage()==CarePackage.REINFORCED_SUIT && suited.getTeam()==uc.getTeam() && uc.getAstronautInfo().getOxygen()>suited.getOxygen()) {
                if (uc.canPerformAction(ActionType.TRANSFER_OXYGEN, dir, 0)) {
                    uc.performAction(ActionType.TRANSFER_OXYGEN, dir, 0);
                    break;
                }
            }
        }
    }

    public Location graphClosest(UnitController uc,StructureInfo[] structures) {
        Location bestLoc=null;
        double distance=Double.POSITIVE_INFINITY;
        for (StructureInfo structure:structures) {
            double value=distanceGraph[structure.getLocation().x-uc.getLocation().x+6][structure.getLocation().y-uc.getLocation().y+6];
            if (value<distance) {
                distance=value;
                bestLoc=structure.getLocation();
            }

        }
        return bestLoc;
    }
    public Location graphClosest(UnitController uc,AstronautInfo[] astronautInfos) {
        Location bestLoc=null;
        double distance=Double.POSITIVE_INFINITY;
        for (AstronautInfo astronautInfo:astronautInfos) {
            double value=distanceGraph[astronautInfo.getLocation().x-uc.getLocation().x+6][astronautInfo.getLocation().y-uc.getLocation().y+6];
            if (value<distance) {
                distance=value;
                bestLoc=astronautInfo.getLocation();
            }

        }
        return bestLoc;
    }
    public Location graphClosest(UnitController uc,CarePackageInfo[] carePackageInfos) {
        Location bestLoc=null;
        double distance=Double.POSITIVE_INFINITY;
        for (CarePackageInfo carePackageInfo:carePackageInfos) {
            double value=distanceGraph[carePackageInfo.getLocation().x-uc.getLocation().x+6][carePackageInfo.getLocation().y-uc.getLocation().y+6];
            if (value<distance) {
                distance=value;
                bestLoc=carePackageInfo.getLocation();
            }

        }
        return bestLoc;
    }
    public Location graphClosest(UnitController uc,Location[] locations) {
        Location bestLoc=null;
        double distance=Double.POSITIVE_INFINITY;
        for (Location location:locations) {
            if (uc.canSenseLocation(location)) {
                double value = distanceGraph[location.x - uc.getLocation().x + 6][location.y - uc.getLocation().y + 6];
                if (value < distance) {
                    distance = value;
                    bestLoc = location;
                }
            }
        }
        return bestLoc;
    }
    public Direction nextGraphMove(UnitController uc, Location location) {
        if (distanceGraph[location.x-uc.getLocation().x+6][location.y-uc.getLocation().y+6]<(float)1.5) {
            return uc.getLocation().directionTo(location);
        }
        else {
            return nextGraphMove(uc,graphClosest(uc,adjacent(location)));
        }
    }
    public Location[] adjacent(Location location) {
        Location[] toReturn=new Location[9];
        for (int i=0; i<8;i++) {
            toReturn[i]=location.add(directions[i]);
        }
        return toReturn;
    }
    public void moveTo(UnitController uc,Location location) {
        Direction dir=nextGraphMove(uc,location);
        if (dir!=null && uc.canPerformAction(ActionType.MOVE,dir,0)) {
            uc.performAction(ActionType.MOVE, dir, 0);
            bugNaving = false;
            uc.drawPointDebug(location, 100, 0, 0);
        }
    }
    public CarePackageInfo[] toPickUp(CarePackageInfo[] seen) {
        int length =0;
        for (CarePackageInfo carePackageInfo:seen) {
            if (carePackageInfo.getCarePackageType()==CarePackage.OXYGEN_TANK || carePackageInfo.getCarePackageType()==CarePackage.SURVIVAL_KIT || carePackageInfo.getCarePackageType()==CarePackage.PLANTS || carePackageInfo.getCarePackageType()==CarePackage.REINFORCED_SUIT) {
                length++;
            }
        }
        CarePackageInfo[] toReturn = new CarePackageInfo[length];
        int index =0;
        for (CarePackageInfo carePackageInfo:seen) {
            if (carePackageInfo.getCarePackageType()==CarePackage.OXYGEN_TANK || carePackageInfo.getCarePackageType()==CarePackage.SURVIVAL_KIT || carePackageInfo.getCarePackageType()==CarePackage.PLANTS || carePackageInfo.getCarePackageType()==CarePackage.REINFORCED_SUIT) {
                toReturn[index]=carePackageInfo;
                index++;
            }
        }
        return toReturn;
    }
    public void broadcastOpponentStructures(UnitController uc) {
        for (StructureInfo structure: uc.senseStructures(GameConstants.ASTRONAUT_VISION_RANGE,uc.getOpponent())) {
            if (!structures.contains(structure.getLocation())) {
                if (uc.canPerformAction(ActionType.BROADCAST,Direction.ZERO, structure.getLocation().y*GameConstants.MAX_MAP_SIZE+structure.getLocation().x)) {
                    uc.performAction(ActionType.BROADCAST,Direction.ZERO, structure.getLocation().y*GameConstants.MAX_MAP_SIZE+structure.getLocation().x);
                }
            }
        }
        for (Location location: structures) {
            if (uc.canSenseLocation(location) && (uc.senseStructure(location)==null || uc.senseStructure(location).getTeam()!=uc.getOpponent())) {
                if (uc.canPerformAction(ActionType.BROADCAST,Direction.ZERO, (int)Math.pow(2,12)+location.y*GameConstants.MAX_MAP_SIZE+location.x)) {
                    uc.performAction(ActionType.BROADCAST,Direction.ZERO, (int)Math.pow(2,12)+location.y*GameConstants.MAX_MAP_SIZE+location.x);
                }
            }
        }
    }
    public void bugNav(UnitController uc,Location location) {
        Direction greedyDir=uc.getLocation().directionTo(location);
        if (!bugNaving || (uc.getLocation().distanceSquared(location)<closestYet && uc.canPerformAction(ActionType.MOVE,greedyDir,0))) {
            followingWall=false;
            turningRight=(int)(uc.getRandomDouble()*2)==0;
            uc.println(turningRight);
            closestYet=uc.getLocation().distanceSquared(location);
            bugNaving=true;
            if (uc.canPerformAction(ActionType.MOVE,greedyDir,0)) {
                uc.performAction(ActionType.MOVE,greedyDir,0);
            }
            else {
                dir=greedyDir;
                Direction oppDir=dir.opposite().opposite();
                if (turningRight) {
                    for (int i = 0; i < 8; ++i) {
                        dir = dir.rotateRight();
                        //Note that the 'value' of the following command is irrelevant.
                        if (uc.canPerformAction(ActionType.MOVE, dir, 0)) {
                            uc.performAction(ActionType.MOVE, dir, 0);
                            followingWall=true;
                            break;
                        }
                        else if (uc.canSenseLocation(uc.getLocation().add(dir))) {
                            AstronautInfo inTheWay=uc.senseAstronaut(uc.getLocation().add(dir));
                            if (inTheWay!=null && inTheWay.getTeam()==uc.getTeam()) {
                                if (!switched) {
                                    switched=true;
                                    dir=oppDir;
                                    turningRight=!turningRight;
                                    bugNav(uc,location);
                                }
                                else {
                                    bugNaving=false;
                                }
                                return;
                            }
                        }
                    }
                }
                else {
                    for (int i = 0; i < 8; ++i) {
                        dir = dir.rotateLeft();
                        //Note that the 'value' of the following command is irrelevant.
                        if (uc.canPerformAction(ActionType.MOVE, dir, 0)) {
                            uc.performAction(ActionType.MOVE, dir, 0);
                            followingWall=true;
                            break;
                        }
                        else if (uc.canSenseLocation(uc.getLocation().add(dir))) {
                            AstronautInfo inTheWay=uc.senseAstronaut(uc.getLocation().add(dir));
                            if (inTheWay!=null && inTheWay.getTeam()==uc.getTeam()) {
                                if (!switched) {
                                    switched=true;
                                    dir=oppDir;
                                    turningRight=!turningRight;
                                    bugNav(uc,location);
                                }
                                else {
                                    bugNaving=false;
                                }
                                return;
                            }
                        }
                    }
                }
            }

        }
        else {
            if (followingWall) {
                dir = dir.opposite();
                Direction oppDir=dir.opposite();
                if (turningRight) {
                    for (int i = 0; i < 8; ++i) {
                        dir = dir.rotateRight();
                        //Note that the 'value' of the following command is irrelevant.
                        if (uc.canPerformAction(ActionType.MOVE, dir, 0)) {
                            uc.performAction(ActionType.MOVE, dir, 0);
                            break;
                        }
                        else if (uc.canSenseLocation(uc.getLocation().add(dir))) {
                            AstronautInfo inTheWay=uc.senseAstronaut(uc.getLocation().add(dir));
                            if (inTheWay!=null && inTheWay.getTeam()==uc.getTeam()) {
                                if (!switched) {
                                    switched=true;
                                    dir=oppDir.rotateRight();
                                    turningRight=!turningRight;
                                    bugNav(uc,location);
                                }
                                else {
                                    bugNaving=false;
                                }
                                return;
                            }
                        }
                    }
                }
                else {
                    for (int i = 0; i < 8; ++i) {
                        dir = dir.rotateLeft();
                        //Note that the 'value' of the following command is irrelevant.
                        if (uc.canPerformAction(ActionType.MOVE, dir, 0)) {
                            uc.performAction(ActionType.MOVE, dir, 0);
                            break;
                        }
                        else if (uc.canSenseLocation(uc.getLocation().add(dir))) {
                            AstronautInfo inTheWay=uc.senseAstronaut(uc.getLocation().add(dir));
                            if (inTheWay!=null && inTheWay.getTeam()==uc.getTeam()) {
                                if (!switched) {
                                    switched=true;
                                    dir=oppDir.rotateLeft();
                                    turningRight=!turningRight;
                                    bugNav(uc,location);
                                }
                                else {
                                    bugNaving=false;
                                }
                                return;
                            }
                        }
                    }
                }
            }

            else {
                if (uc.canPerformAction(ActionType.MOVE,greedyDir,0)) {
                    uc.performAction(ActionType.MOVE,greedyDir,0);
                }
                else {
                    dir=greedyDir;
                    Direction oppDir=dir.opposite().opposite();
                    if (turningRight) {
                        for (int i = 0; i < 8; ++i) {
                            dir = dir.rotateRight();
                            //Note that the 'value' of the following command is irrelevant.
                            if (uc.canPerformAction(ActionType.MOVE, dir, 0)) {
                                uc.performAction(ActionType.MOVE, dir, 0);
                                followingWall=true;
                                break;
                            }
                            else if (uc.canSenseLocation(uc.getLocation().add(dir))) {
                                AstronautInfo inTheWay=uc.senseAstronaut(uc.getLocation().add(dir));
                                if (inTheWay!=null && inTheWay.getTeam()==uc.getTeam()) {
                                    if (!switched) {
                                        switched=true;
                                        dir=oppDir;
                                        turningRight=!turningRight;
                                        bugNav(uc,location);
                                    }
                                    else {
                                        bugNaving=false;
                                    }
                                    return;
                                }
                            }
                        }
                    }
                    else {
                        for (int i = 0; i < 8; ++i) {
                            dir = dir.rotateLeft();
                            //Note that the 'value' of the following command is irrelevant.
                            if (uc.canPerformAction(ActionType.MOVE, dir, 0)) {
                                uc.performAction(ActionType.MOVE, dir, 0);
                                followingWall=true;
                                break;
                            }
                            else if (uc.canSenseLocation(uc.getLocation().add(dir))) {
                                AstronautInfo inTheWay=uc.senseAstronaut(uc.getLocation().add(dir));
                                if (inTheWay!=null && inTheWay.getTeam()==uc.getTeam()) {
                                    if (!switched) {
                                        switched=true;
                                        dir=oppDir;
                                        turningRight=!turningRight;
                                        bugNav(uc,location);
                                    }
                                    else {
                                        bugNaving=false;
                                    }
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    public void updateDistanceGraph(UnitController uc) {
        Location location;
        location=uc.getLocation().add(1,0);
        distanceGraph[7][6]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[7][6]=1;
        location=uc.getLocation().add(-1,0);
        distanceGraph[5][6]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[5][6]=1;
        location=uc.getLocation().add(0,1);
        distanceGraph[6][7]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[6][7]=1;
        location=uc.getLocation().add(0,-1);
        distanceGraph[6][5]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[6][5]=1;
        location=uc.getLocation().add(1,1);
        distanceGraph[7][7]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[7][7]=1.4142;
        location=uc.getLocation().add(-1,-1);
        distanceGraph[5][5]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[5][5]=1.4142;
        location=uc.getLocation().add(-1,1);
        distanceGraph[5][7]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[5][7]=1.4142;
        location=uc.getLocation().add(1,-1);
        distanceGraph[7][5]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[7][5]=1.4142;

        location=uc.getLocation().add(-2,0);
        distanceGraph[4][6]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[4][6]=Math.min(Math.min(distanceGraph[5][6]+1,distanceGraph[5][7]+1.4142),distanceGraph[5][5]+1.4142);
        location=uc.getLocation().add(0,-2);
        distanceGraph[6][4]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[6][4]=Math.min(Math.min(distanceGraph[6][5]+1,distanceGraph[5][5]+1.4142),distanceGraph[7][5]+1.4142);
        location=uc.getLocation().add(0,2);
        distanceGraph[6][8]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[6][8]=Math.min(Math.min(distanceGraph[6][7]+1,distanceGraph[5][7]+1.4142),distanceGraph[7][7]+1.4142);
        location=uc.getLocation().add(2,0);
        distanceGraph[8][6]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[8][6]=Math.min(Math.min(distanceGraph[7][6]+1,distanceGraph[7][7]+1.4142),distanceGraph[7][5]+1.4142);
        location=uc.getLocation().add(-2,-1);
        distanceGraph[4][5]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[4][5]=Math.min(Math.min(distanceGraph[5][5]+1,distanceGraph[4][6]+1),distanceGraph[5][6]+1.4142);
        location=uc.getLocation().add(-2,1);
        distanceGraph[4][7]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[4][7]=Math.min(Math.min(distanceGraph[5][7]+1,distanceGraph[4][6]+1),distanceGraph[5][6]+1.4142);
        location=uc.getLocation().add(-1,-2);
        distanceGraph[5][4]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[5][4]=Math.min(Math.min(distanceGraph[6][4]+1,distanceGraph[5][5]+1),distanceGraph[6][5]+1.4142);
        location=uc.getLocation().add(-1,2);
        distanceGraph[5][8]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[5][8]=Math.min(Math.min(distanceGraph[6][8]+1,distanceGraph[5][7]+1),distanceGraph[6][7]+1.4142);
        location=uc.getLocation().add(1,-2);
        distanceGraph[7][4]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[7][4]=Math.min(Math.min(distanceGraph[6][4]+1,distanceGraph[7][5]+1),distanceGraph[6][5]+1.4142);
        location=uc.getLocation().add(1,2);
        distanceGraph[7][8]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[7][8]=Math.min(Math.min(distanceGraph[6][8]+1,distanceGraph[7][7]+1),distanceGraph[6][7]+1.4142);
        location=uc.getLocation().add(2,-1);
        distanceGraph[8][5]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[8][5]=Math.min(Math.min(distanceGraph[7][5]+1,distanceGraph[8][6]+1),distanceGraph[7][6]+1.4142);
        location=uc.getLocation().add(2,1);
        distanceGraph[8][7]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[8][7]=Math.min(Math.min(distanceGraph[7][7]+1,distanceGraph[8][6]+1),distanceGraph[7][6]+1.4142);
        location=uc.getLocation().add(-2,-2);
        distanceGraph[4][4]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[4][4]=Math.min(Math.min(distanceGraph[5][4]+1,distanceGraph[4][5]+1),distanceGraph[5][5]+1.4142);
        location=uc.getLocation().add(-2,2);
        distanceGraph[4][8]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[4][8]=Math.min(Math.min(distanceGraph[5][8]+1,distanceGraph[4][7]+1),distanceGraph[5][7]+1.4142);
        location=uc.getLocation().add(2,-2);
        distanceGraph[8][4]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[8][4]=Math.min(Math.min(distanceGraph[7][4]+1,distanceGraph[8][5]+1),distanceGraph[7][5]+1.4142);
        location=uc.getLocation().add(2,2);
        distanceGraph[8][8]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[8][8]=Math.min(Math.min(distanceGraph[7][8]+1,distanceGraph[8][7]+1),distanceGraph[7][7]+1.4142);
        location=uc.getLocation().add(-3,0);
        distanceGraph[3][6]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[3][6]=Math.min(Math.min(distanceGraph[4][6]+1,distanceGraph[4][7]+1.4142),distanceGraph[4][5]+1.4142);
        location=uc.getLocation().add(0,-3);
        distanceGraph[6][3]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[6][3]=Math.min(Math.min(distanceGraph[6][4]+1,distanceGraph[5][4]+1.4142),distanceGraph[7][4]+1.4142);
        location=uc.getLocation().add(0,3);
        distanceGraph[6][9]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[6][9]=Math.min(Math.min(distanceGraph[6][8]+1,distanceGraph[5][8]+1.4142),distanceGraph[7][8]+1.4142);
        location=uc.getLocation().add(3,0);
        distanceGraph[9][6]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[9][6]=Math.min(Math.min(distanceGraph[8][6]+1,distanceGraph[8][7]+1.4142),distanceGraph[8][5]+1.4142);
        location=uc.getLocation().add(-3,-1);
        distanceGraph[3][5]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[3][5]=Math.min(Math.min(distanceGraph[4][5]+1,distanceGraph[3][6]+1),distanceGraph[4][6]+1.4142);
        location=uc.getLocation().add(-3,1);
        distanceGraph[3][7]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[3][7]=Math.min(Math.min(distanceGraph[4][7]+1,distanceGraph[3][6]+1),distanceGraph[4][6]+1.4142);
        location=uc.getLocation().add(-1,-3);
        distanceGraph[5][3]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[5][3]=Math.min(Math.min(distanceGraph[6][3]+1,distanceGraph[5][4]+1),distanceGraph[6][4]+1.4142);
        location=uc.getLocation().add(-1,3);
        distanceGraph[5][9]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[5][9]=Math.min(Math.min(distanceGraph[6][9]+1,distanceGraph[5][8]+1),distanceGraph[6][8]+1.4142);
        location=uc.getLocation().add(1,-3);
        distanceGraph[7][3]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[7][3]=Math.min(Math.min(distanceGraph[6][3]+1,distanceGraph[7][4]+1),distanceGraph[6][4]+1.4142);
        location=uc.getLocation().add(1,3);
        distanceGraph[7][9]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[7][9]=Math.min(Math.min(distanceGraph[6][9]+1,distanceGraph[7][8]+1),distanceGraph[6][8]+1.4142);
        location=uc.getLocation().add(3,-1);
        distanceGraph[9][5]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[9][5]=Math.min(Math.min(distanceGraph[8][5]+1,distanceGraph[9][6]+1),distanceGraph[8][6]+1.4142);
        location=uc.getLocation().add(3,1);
        distanceGraph[9][7]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[9][7]=Math.min(Math.min(distanceGraph[8][7]+1,distanceGraph[9][6]+1),distanceGraph[8][6]+1.4142);
        location=uc.getLocation().add(-3,-2);
        distanceGraph[3][4]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[3][4]=Math.min(Math.min(distanceGraph[4][4]+1,distanceGraph[3][5]+1),distanceGraph[4][5]+1.4142);
        location=uc.getLocation().add(-3,2);
        distanceGraph[3][8]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[3][8]=Math.min(Math.min(distanceGraph[4][8]+1,distanceGraph[3][7]+1),distanceGraph[4][7]+1.4142);
        location=uc.getLocation().add(-2,-3);
        distanceGraph[4][3]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[4][3]=Math.min(Math.min(distanceGraph[5][3]+1,distanceGraph[4][4]+1),distanceGraph[5][4]+1.4142);
        location=uc.getLocation().add(-2,3);
        distanceGraph[4][9]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[4][9]=Math.min(Math.min(distanceGraph[5][9]+1,distanceGraph[4][8]+1),distanceGraph[5][8]+1.4142);
        location=uc.getLocation().add(2,-3);
        distanceGraph[8][3]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[8][3]=Math.min(Math.min(distanceGraph[7][3]+1,distanceGraph[8][4]+1),distanceGraph[7][4]+1.4142);
        location=uc.getLocation().add(2,3);
        distanceGraph[8][9]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[8][9]=Math.min(Math.min(distanceGraph[7][9]+1,distanceGraph[8][8]+1),distanceGraph[7][8]+1.4142);
        location=uc.getLocation().add(3,-2);
        distanceGraph[9][4]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[9][4]=Math.min(Math.min(distanceGraph[8][4]+1,distanceGraph[9][5]+1),distanceGraph[8][5]+1.4142);
        location=uc.getLocation().add(3,2);
        distanceGraph[9][8]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[9][8]=Math.min(Math.min(distanceGraph[8][8]+1,distanceGraph[9][7]+1),distanceGraph[8][7]+1.4142);
        location=uc.getLocation().add(-4,0);
        distanceGraph[2][6]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[2][6]=Math.min(Math.min(distanceGraph[3][6]+1,distanceGraph[3][7]+1.4142),distanceGraph[3][5]+1.4142);
        location=uc.getLocation().add(0,-4);
        distanceGraph[6][2]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[6][2]=Math.min(Math.min(distanceGraph[6][3]+1,distanceGraph[5][3]+1.4142),distanceGraph[7][3]+1.4142);
        location=uc.getLocation().add(0,4);
        distanceGraph[6][10]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[6][10]=Math.min(Math.min(distanceGraph[6][9]+1,distanceGraph[5][9]+1.4142),distanceGraph[7][9]+1.4142);
        location=uc.getLocation().add(4,0);
        distanceGraph[10][6]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[10][6]=Math.min(Math.min(distanceGraph[9][6]+1,distanceGraph[9][7]+1.4142),distanceGraph[9][5]+1.4142);
        location=uc.getLocation().add(-4,-1);
        distanceGraph[2][5]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[2][5]=Math.min(Math.min(distanceGraph[3][5]+1,distanceGraph[2][6]+1),distanceGraph[3][6]+1.4142);
        location=uc.getLocation().add(-4,1);
        distanceGraph[2][7]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[2][7]=Math.min(Math.min(distanceGraph[3][7]+1,distanceGraph[2][6]+1),distanceGraph[3][6]+1.4142);
        location=uc.getLocation().add(-1,-4);
        distanceGraph[5][2]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[5][2]=Math.min(Math.min(distanceGraph[6][2]+1,distanceGraph[5][3]+1),distanceGraph[6][3]+1.4142);
        location=uc.getLocation().add(-1,4);
        distanceGraph[5][10]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[5][10]=Math.min(Math.min(distanceGraph[6][10]+1,distanceGraph[5][9]+1),distanceGraph[6][9]+1.4142);
        location=uc.getLocation().add(1,-4);
        distanceGraph[7][2]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[7][2]=Math.min(Math.min(distanceGraph[6][2]+1,distanceGraph[7][3]+1),distanceGraph[6][3]+1.4142);
        location=uc.getLocation().add(1,4);
        distanceGraph[7][10]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[7][10]=Math.min(Math.min(distanceGraph[6][10]+1,distanceGraph[7][9]+1),distanceGraph[6][9]+1.4142);
        location=uc.getLocation().add(4,-1);
        distanceGraph[10][5]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[10][5]=Math.min(Math.min(distanceGraph[9][5]+1,distanceGraph[10][6]+1),distanceGraph[9][6]+1.4142);
        location=uc.getLocation().add(4,1);
        distanceGraph[10][7]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[10][7]=Math.min(Math.min(distanceGraph[9][7]+1,distanceGraph[10][6]+1),distanceGraph[9][6]+1.4142);
        location=uc.getLocation().add(-3,-3);
        distanceGraph[3][3]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[3][3]=Math.min(Math.min(distanceGraph[4][3]+1,distanceGraph[3][4]+1),distanceGraph[4][4]+1.4142);
        location=uc.getLocation().add(-3,3);
        distanceGraph[3][9]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[3][9]=Math.min(Math.min(distanceGraph[4][9]+1,distanceGraph[3][8]+1),distanceGraph[4][8]+1.4142);
        location=uc.getLocation().add(3,-3);
        distanceGraph[9][3]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[9][3]=Math.min(Math.min(distanceGraph[8][3]+1,distanceGraph[9][4]+1),distanceGraph[8][4]+1.4142);
        location=uc.getLocation().add(3,3);
        distanceGraph[9][9]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[9][9]=Math.min(Math.min(distanceGraph[8][9]+1,distanceGraph[9][8]+1),distanceGraph[8][8]+1.4142);
        location=uc.getLocation().add(-4,-2);
        distanceGraph[2][4]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[2][4]=Math.min(Math.min(distanceGraph[3][4]+1,distanceGraph[2][5]+1),distanceGraph[3][5]+1.4142);
        location=uc.getLocation().add(-4,2);
        distanceGraph[2][8]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[2][8]=Math.min(Math.min(distanceGraph[3][8]+1,distanceGraph[2][7]+1),distanceGraph[3][7]+1.4142);
        location=uc.getLocation().add(-2,-4);
        distanceGraph[4][2]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[4][2]=Math.min(Math.min(distanceGraph[5][2]+1,distanceGraph[4][3]+1),distanceGraph[5][3]+1.4142);
        location=uc.getLocation().add(-2,4);
        distanceGraph[4][10]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[4][10]=Math.min(Math.min(distanceGraph[5][10]+1,distanceGraph[4][9]+1),distanceGraph[5][9]+1.4142);
        location=uc.getLocation().add(2,-4);
        distanceGraph[8][2]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[8][2]=Math.min(Math.min(distanceGraph[7][2]+1,distanceGraph[8][3]+1),distanceGraph[7][3]+1.4142);
        location=uc.getLocation().add(2,4);
        distanceGraph[8][10]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[8][10]=Math.min(Math.min(distanceGraph[7][10]+1,distanceGraph[8][9]+1),distanceGraph[7][9]+1.4142);
        location=uc.getLocation().add(4,-2);
        distanceGraph[10][4]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[10][4]=Math.min(Math.min(distanceGraph[9][4]+1,distanceGraph[10][5]+1),distanceGraph[9][5]+1.4142);
        location=uc.getLocation().add(4,2);
        distanceGraph[10][8]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[10][8]=Math.min(Math.min(distanceGraph[9][8]+1,distanceGraph[10][7]+1),distanceGraph[9][7]+1.4142);
        location=uc.getLocation().add(-5,0);
        distanceGraph[1][6]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[1][6]=Math.min(Math.min(distanceGraph[2][6]+1,distanceGraph[2][7]+1.4142),distanceGraph[2][5]+1.4142);
        location=uc.getLocation().add(-4,-3);
        distanceGraph[2][3]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[2][3]=Math.min(Math.min(distanceGraph[3][3]+1,distanceGraph[2][4]+1),distanceGraph[3][4]+1.4142);
        location=uc.getLocation().add(-4,3);
        distanceGraph[2][9]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[2][9]=Math.min(Math.min(distanceGraph[3][9]+1,distanceGraph[2][8]+1),distanceGraph[3][8]+1.4142);
        location=uc.getLocation().add(-3,-4);
        distanceGraph[3][2]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[3][2]=Math.min(Math.min(distanceGraph[4][2]+1,distanceGraph[3][3]+1),distanceGraph[4][3]+1.4142);
        location=uc.getLocation().add(-3,4);
        distanceGraph[3][10]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[3][10]=Math.min(Math.min(distanceGraph[4][10]+1,distanceGraph[3][9]+1),distanceGraph[4][9]+1.4142);
        location=uc.getLocation().add(0,-5);
        distanceGraph[6][1]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[6][1]=Math.min(Math.min(distanceGraph[6][2]+1,distanceGraph[5][2]+1.4142),distanceGraph[7][2]+1.4142);
        location=uc.getLocation().add(0,5);
        distanceGraph[6][11]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[6][11]=Math.min(Math.min(distanceGraph[6][10]+1,distanceGraph[5][10]+1.4142),distanceGraph[7][10]+1.4142);
        location=uc.getLocation().add(3,-4);
        distanceGraph[9][2]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[9][2]=Math.min(Math.min(distanceGraph[8][2]+1,distanceGraph[9][3]+1),distanceGraph[8][3]+1.4142);
        location=uc.getLocation().add(3,4);
        distanceGraph[9][10]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[9][10]=Math.min(Math.min(distanceGraph[8][10]+1,distanceGraph[9][9]+1),distanceGraph[8][9]+1.4142);
        location=uc.getLocation().add(4,-3);
        distanceGraph[10][3]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[10][3]=Math.min(Math.min(distanceGraph[9][3]+1,distanceGraph[10][4]+1),distanceGraph[9][4]+1.4142);
        location=uc.getLocation().add(4,3);
        distanceGraph[10][9]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[10][9]=Math.min(Math.min(distanceGraph[9][9]+1,distanceGraph[10][8]+1),distanceGraph[9][8]+1.4142);
        location=uc.getLocation().add(5,0);
        distanceGraph[11][6]=Double.POSITIVE_INFINITY;
        if (uc.canSenseLocation(location) && !uc.senseTileType(location).equals(TileType.WATER)) distanceGraph[11][6]=Math.min(Math.min(distanceGraph[10][6]+1,distanceGraph[10][7]+1.4142),distanceGraph[10][5]+1.4142);
    }
    public void getAttack(UnitController uc) {
        float myOx = uc.getAstronautInfo().getOxygen();
        AstronautInfo[] potentials = uc.senseAstronauts(GameConstants.ASTRONAUT_VISION_RANGE,uc.getOpponent());
        int sizeA=0;
        for (AstronautInfo enemyAstro:potentials) {
            if (enemyAstro.getOxygen() >= myOx) {
                sizeA++;
            }
        }
        attack = new AstronautInfo[sizeA];

        sizeA=0;
        for (AstronautInfo enemy:potentials) {
            if (enemy.getOxygen()>=myOx) {
                attack[sizeA]=enemy;
                sizeA++;
            }
        }
    }
}
