from fabric.api import run,cd,parallel,serial
from fabric.contrib.files import exists
counter = 0

@parallel
def cmt_init():
    if not exists('/data/mdindex/yilu'):
        run('mkdir /data/mdindex/yilu')
    with cd('/data/mdindex/yilu'):
        run('git clone https://github.com/luyi0619/cmt-dbgen.git')

    with cd('/data/mdindex/yilu/cmt-dbgen'):
        run('javac src/dataGenerator.java')

@serial
def cmt_script_gen_100000000():
    global counter
    try:
        with cd('/data/mdindex/yilu/cmt-dbgen'):
            try:
                # delete any old tables before creating new ones
                run('rm -rf *txt*')
            except:
                pass
            
            script = "#!/bin/bash\n"
            script += "cd /data/mdindex/yilu/cmt-dbgen/src\n"
            cmd = 'java dataGenerator ../dist 10 %d 100000000\n' % counter
            script += cmd

            run('echo "%s" > data_gen.sh' % script)
            run('chmod +x data_gen.sh')
    except:
        pass

    counter += 1


@parallel
def cmt_gen():
    run('nohup /data/mdindex/yilu/cmt-dbgen/data_gen.sh  > /dev/null 2>&1 < /dev/null &', pty=False)


@serial
def cmt_move_data_100000000():
    if exists('/data/mdindex/yilu'):
        run('rm -rf /data/mdindex/yilu/cmt100000000')
    run('mkdir /data/mdindex/yilu/cmt100000000')
    run('mv /data/mdindex/yilu/cmt-dbgen/src/mapmatch_history.txt.* /data/mdindex/yilu/cmt100000000')
    run('mv /data/mdindex/yilu/cmt-dbgen/src/mapmatch_history_latest.txt.* /data/mdindex/yilu/cmt100000000')
    run('mv /data/mdindex/yilu/cmt-dbgen/src/sf_datasets.txt.9.* /data/mdindex/yilu/cmt100000000')
    run('rm -rf /data/mdindex/yilu/cmt-dbgen/src/*txt*')
    
