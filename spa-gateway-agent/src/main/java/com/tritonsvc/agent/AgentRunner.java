package com.tritonsvc.agent;

/**
 * Created 8/31/16.
 */
public class AgentRunner implements Runnable {
    private Agent agent;
    private String homepath;
    private int threadNumber;

    public AgentRunner(int threadNumber, String homepath) {
        this.threadNumber = threadNumber;
        this.homepath = homepath;
    }

    @Override
    public void run() {
        agent = new Agent();
        agent.setThreadId(Integer.toString(threadNumber));
        agent.start(homepath);
        System.out.println("*** started thread " + threadNumber);
    }


}
