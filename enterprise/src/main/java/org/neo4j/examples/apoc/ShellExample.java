package org.neo4j.examples.apoc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.EmbeddedGraphDatabase;
import org.neo4j.shell.ShellServer;
import org.neo4j.shell.impl.SameJvmClient;
import org.neo4j.shell.kernel.GraphDatabaseShellServer;

public class ShellExample
{
    private static final String DB_PATH = "neo4j-store";
    private static final String USERNAME_KEY = "username";
    
    private static GraphDatabaseService graphDb;
    
    private static enum RelTypes implements RelationshipType
    {
        USERS_REFERENCE,
        USER,
        KNOWS,
    }
    
    public static void main( String[] args ) throws Exception
    {
        registerShutdownHookForNeo();
        
        startGraphDb();
        createExampleNodeSpace();
        boolean trueForLocal = waitForUserInput( "Would you like to start a " +
            "local shell instance or enable neo4j to accept remote " +
            "connections [l/r]? " ).equalsIgnoreCase( "l" );
        if ( trueForLocal )
        {
            startLocalShell();
        }
        else
        {
            startRemoteShellAndWait();
        }
        
        System.out.println( "Shutting down..." );
        shutdown();
    }
    
    private static void startGraphDb()
    {
        graphDb = new EmbeddedGraphDatabase( DB_PATH );
    }
    
    private static void startLocalShell() throws Exception
    {
        ShellServer shellServer = new GraphDatabaseShellServer( graphDb );
        new SameJvmClient( shellServer ).grabPrompt();
        shellServer.shutdown();
    }

    private static void startRemoteShellAndWait() throws Exception
    {
        graphDb.enableRemoteShell();
        waitForUserInput( "Remote shell enabled, connect to it by running:\n" +
            "java -jar lib/shell-<version>.jar\n" +
            "\nWhen you're done playing around, just press any key " +
            "in this terminal " );
    }
    
    private static String waitForUserInput( String textToSystemOut )
        throws Exception
    {
        System.out.print( textToSystemOut );
        return new BufferedReader(
            new InputStreamReader( System.in ) ).readLine();
    }

    private static void createExampleNodeSpace()
    {
        Transaction tx = graphDb.beginTx();
        try
        {
            // Create users sub reference node (see design guide lines on
            // http://wiki.neo4j.org)
            System.out.println( "Creating example node space..." );
            Random random = new Random();
            Node usersReferenceNode = graphDb.createNode();
            graphDb.getReferenceNode().createRelationshipTo( usersReferenceNode,
                RelTypes.USERS_REFERENCE );
            
            // Create some users and index their names with the IndexService
            List<Node> users = new ArrayList<Node>();
            for ( int id = 0; id < 100; id++ )
            {
                Node userNode = createUser( formUserName( id ) ); 
                usersReferenceNode.createRelationshipTo( userNode,
                    RelTypes.USER );
                if ( id > 10 )
                {
                    int numberOfFriends = random.nextInt( 5 );
                    Set<Node> knows = new HashSet<Node>();
                    for ( int i = 0; i < numberOfFriends; i++ )
                    {
                        Node friend = users.get( random.nextInt(
                            users.size() ) );
                        if ( knows.add( friend ) )
                        {
                            userNode.createRelationshipTo( friend,
                                RelTypes.KNOWS );
                        }
                    }
                }
                users.add( userNode );
            }
            tx.success();
        }
        finally
        {
            tx.finish();
        }
    }
    
    private static void deleteExampleNodeSpace()
    {
        Transaction tx = graphDb.beginTx();
        try
        {
            // Delete the persons and remove them from the index
            System.out.println( "Deleting example node space..." );
            Node usersReferenceNode =
                graphDb.getReferenceNode().getSingleRelationship(
                    RelTypes.USERS_REFERENCE, Direction.OUTGOING ).getEndNode();
            for ( Relationship relationship :
                usersReferenceNode.getRelationships( RelTypes.USER,
                    Direction.OUTGOING ) )
            {
                Node user = relationship.getEndNode();
                for ( Relationship knowsRelationship : user.getRelationships(
                    RelTypes.KNOWS ) )
                {
                    knowsRelationship.delete();
                }
                user.delete();
                relationship.delete();
            }
            usersReferenceNode.getSingleRelationship( RelTypes.USERS_REFERENCE,
                Direction.INCOMING ).delete();
            usersReferenceNode.delete();
            tx.success();
        }
        finally
        {
            tx.finish();
        }
    }
    
    private static void shutdownNeo()
    {
        graphDb.shutdown();
        graphDb = null;
    }
    
    private static void shutdown()
    {
        if ( graphDb != null )
        {
            deleteExampleNodeSpace();
            shutdownNeo();
        }
    }
    
    private static void registerShutdownHookForNeo()
    {
        // Registers a shutdown hook for the Neo4j instance so that it
        // shuts down nicely when the VM exits (even if you "Ctrl-C" the
        // running example before it's completed)
        Runtime.getRuntime().addShutdownHook( new Thread()
        {
            @Override
            public void run()
            {
                shutdown();
            }
        } );
    }
    
    private static String formUserName( int id )
    {
        return "user" + id + "@neo4j.org";
    }

    private static Node createUser( String username )
    {
        Node node = graphDb.createNode();
        node.setProperty( USERNAME_KEY, username );
        return node;
    }
}
