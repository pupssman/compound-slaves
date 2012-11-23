compound-slaves
======================

compound-slaves is a Jenkins plugin for uniting separate slaves.

It allows for creation of multi-node slave, that has access to executors on sub-slaves and provides a build step, proxying the actual step to the given slave.

It also contains clouding capability -- i.e. on-the-fly compounding of newly-provisioned slaves by a given pattern.
