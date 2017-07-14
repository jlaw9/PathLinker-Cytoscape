package com.dpgil.pathlinker.path_linker.internal;

import com.dpgil.pathlinker.path_linker.internal.Algorithms.Path;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import org.cytoscape.app.CyAppAdapter;
import org.cytoscape.application.CyApplicationManager;
import org.cytoscape.application.swing.CySwingApplication;
import org.cytoscape.application.swing.CytoPanel;
import org.cytoscape.application.swing.CytoPanelComponent;
import org.cytoscape.application.swing.CytoPanelName;
import org.cytoscape.application.swing.CytoPanelState;
import org.cytoscape.model.CyEdge;
import org.cytoscape.model.CyNetwork;
import org.cytoscape.model.CyNetworkFactory;
import org.cytoscape.model.CyNetworkManager;
import org.cytoscape.model.CyNode;
import org.cytoscape.model.CyRow;
import org.cytoscape.model.CyTable;
import org.cytoscape.model.subnetwork.CyRootNetwork;
import org.cytoscape.model.subnetwork.CySubNetwork;
import org.cytoscape.view.layout.CyLayoutAlgorithm;
import org.cytoscape.view.model.CyNetworkView;
import org.cytoscape.view.model.CyNetworkViewFactory;
import org.cytoscape.view.model.CyNetworkViewManager;
import org.cytoscape.view.model.View;
import org.cytoscape.view.presentation.property.BasicVisualLexicon;
import org.cytoscape.view.presentation.property.NodeShapeVisualProperty;
import org.cytoscape.work.SynchronousTaskManager;
import org.cytoscape.work.TaskIterator;

/** Panel for the PathLinker plugin */
public class PathLinkerPanel extends JPanel implements CytoPanelComponent {
	/** UI components of the panel */
	private JLabel _sourcesLabel;
	private JLabel _targetsLabel;
	private JLabel _kLabel;
	private JLabel _edgePenaltyLabel;
	private JTextField _sourcesTextField;
	private JTextField _targetsTextField;
	private JTextField _kTextField;
	private JTextField _edgePenaltyTextField;
	private JButton _submitButton;
	private ButtonGroup _weightedOptionGroup;
	private JRadioButton _unweighted;
	private JRadioButton _weightedAdditive;
	private JRadioButton _weightedProbabilities;
	private JCheckBox _subgraphOption;
	private JCheckBox _allowSourcesTargetsInPathsOption;
	private JCheckBox _targetsSameAsSourcesOption;
	private JLabel _runningMessage;

	/** Cytoscape class for network and view management */
	private CySwingApplication _cySwingApp;
	private CyApplicationManager _applicationManager;
	private CyNetworkManager _networkManager;
	private CyNetworkViewFactory _networkViewFactory;
	private CyNetworkViewManager _networkViewManager;
	private CyAppAdapter _adapter;

	/** The network to perform the algorithm on */
	private CyNetwork _network;
	/** Parent container of the panel to re add to when we call open */
	private Container _parent;
	/** State of the panel. Initially null b/c it isn't open or closed yet */
	private PanelState _state = null;
	/** Index of the tab in the parent panel */
	private int _tabIndex;
	/** Whether or not to generate a subgraph */
	private boolean _generateSubgraph;
	private boolean _allEdgesContainWeights = true;
	
	/** The state of the panel */
	public enum PanelState {
		/** The panel is hidden */
		CLOSED,
		/** The panel is visible */
		OPEN
	};

	/**
	 * Sets the state of the panel (open or closed).
	 *
	 * @param newState
	 *            the new state
	 */
	public void setPanelState(PanelState newState) {
		if (newState == _state) {
			// occurs when panel is already "open" (it's in the cytopanel)
			// so we don't need to re add it to the panel, just set it as
			// selected
			if (newState == PanelState.OPEN) {
				CytoPanel cytoPanel = _cySwingApp.getCytoPanel(getCytoPanelName());
				if (cytoPanel.getState() == CytoPanelState.HIDE) {
					cytoPanel.setState(CytoPanelState.DOCK);
				}
				setVisible(true);
				// The panel is selected upon clicking PathLinker -> Open
				cytoPanel.setSelectedIndex(cytoPanel.indexOfComponent(getComponent()));
			}

			return;
		}

		if (newState == PanelState.CLOSED) {
			_state = PanelState.CLOSED;
			_parent.remove(this);
		}
		// only occurs if panel is previously closed
		else if (newState == PanelState.OPEN) {
			_state = PanelState.OPEN;
			((JTabbedPane) _parent).addTab(this.getTitle(), this);
			CytoPanel cytoPanel = _cySwingApp.getCytoPanel(getCytoPanelName());
			if (cytoPanel.getState() == CytoPanelState.HIDE) {
				cytoPanel.setState(CytoPanelState.DOCK);
			}
			setVisible(true);
			// The panel is selected upon clicking PathLinker -> Open
			cytoPanel.setSelectedIndex(cytoPanel.indexOfComponent(getComponent()));
		}

		this.revalidate();
		this.repaint();
	}

	/**
	 * Constructor for the panel Initializes the visual elements in the panel
	 */
	public PathLinkerPanel() {
		initializePanelItems();
	}

	/**
	 * Initializer for the panel to reduce the number of parameters in the
	 * constructor
	 *
	 * @param applicationManager
	 *            application manager
	 * @param networkManager
	 *            network manager
	 * @param networkViewFactory
	 *            network view factory
	 * @param networkViewManager
	 *            network view manager
	 * @param adapter
	 *            the cy application adapter
	 */
	public void initialize(CySwingApplication cySwingApp, CyApplicationManager applicationManager,
			CyNetworkManager networkManager, CyNetworkViewFactory networkViewFactory,
			CyNetworkViewManager networkViewManager, CyAppAdapter adapter) {
		_cySwingApp = cySwingApp;
		_applicationManager = applicationManager;
		_networkManager = networkManager;
		_networkViewFactory = networkViewFactory;
		_networkViewManager = networkViewManager;
		_adapter = adapter;
		_parent = this.getParent();
	}

	/** Listener for the submit button in the panel */
	class SubmitButtonListener implements ActionListener {
		/**
		 * Responds to a click of the submit button in the pathlinker jpanel
		 */
		@Override
		public void actionPerformed(ActionEvent e) {
			prepareAndRunKSP();
		}
	}

	private void prepareAndRunKSP() {
		showRunningMessage();

		// checks for identical sources/targets option selection to
		// update the panel values
		if (_targetsSameAsSourcesOption.isSelected()) {
			_targetsTextField.setText(_sourcesTextField.getText());
			_allowSourcesTargetsInPathsOption.setSelected(true);
		}

		// this looks extremely stupid, but is very important.
		// due to the multi-threaded nature of the swing gui, if
		// this were simply runKSP() and then hideRunningMessage(), java
		// would assign a thread to the hideRunningMessage and we would
		// never see the "PathLinker is running..." message. By adding
		// the if else we force the program to wait on the result of
		// runKSP and thus peforming these events in the order we want
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				if (runKSP()) {
					hideRunningMessage();
				} else {
					hideRunningMessage();
				}
			}
		});

	}

	private void showRunningMessage() {
		_runningMessage.setVisible(true);
		_runningMessage.setForeground(Color.BLUE);
		add(_runningMessage, BorderLayout.SOUTH);
		repaint();
		revalidate();
	}

	private void hideRunningMessage() {
		remove(_runningMessage);
		repaint();
		revalidate();
	}

	/**
	 * Main driving method for the KSP algorithm Makes all the calls for
	 * preprocessing and displaying the results
	 */
	private boolean runKSP() {
		boolean success;

		PathLinkerModel model = new PathLinkerModel(_applicationManager.getCurrentNetwork(), 
				_allowSourcesTargetsInPathsOption.isSelected());

		// populates a mapping from the name of a node to the actual node object
		// used for converting user input to node objects. populates the map
		// named _idToCyNode. is unsuccessful if there is no network
		success = model.populateIdToCyNode();
		if (!success)
			return false;

		// reads the raw values from the panel and converts them into useful
		// objects to be used in the algorithms
		model = readValuesFromPanel(model);
		if (model == null)
			return false;

		model.runKSP();

		// runs the KSP algorithm
		ArrayList<Path> result = model.runKSP();
		
		// generates a subgraph of the nodes and edges involved in the resulting
		// paths and displays it to the user
		if (_generateSubgraph)
			createKSPSubgraph(result, model);
		
		// writes the result of the algorithm to a table
		writeResult(result, model);

		return true;
	}

	/**
	 * Reads in the raw values from the panel and converts them to useful
	 * objects that can be used for the algorithm. Performs error checking on
	 * the values and warns the user if it is a minor error or quits if there
	 * are any major errors.
	 *
	 * @return true if the parsing was successful, false otherwise
	 */
	private PathLinkerModel readValuesFromPanel(PathLinkerModel model) {
		// error message to report errors to the user if they occur
		StringBuilder errorMessage = new StringBuilder();

		ArrayList<String> sourcesNotInNet = model.setSourceAndSourceNames(_sourcesTextField.getText());
		ArrayList<String> targetsNotInNet = model.setTargetAndTargetNames(_targetsTextField.getText());
		ArrayList<CyNode> sources = model.getSourcesList();
		ArrayList<CyNode> targets = model.getTargetsList();

		// makes sure that we actually have at least one valid source and target
		if (sources.size() == 0) {
			JOptionPane.showMessageDialog(null, "There are no valid sources to be used. Quitting...");
			return null;
		}
		if (targets.size() == 0) {
			JOptionPane.showMessageDialog(null, "There are no valid targets to be used. Quitting...");
			return null;
		}

		// appends all missing sources/targets to the error message
		if (sourcesNotInNet != null) {
			errorMessage.append("The sources " + sourcesNotInNet.toString() + " are not in the network.\n");
		}
		if (targetsNotInNet != null) {
			errorMessage.append("The targets " + targetsNotInNet.toString() + " are not in the network.\n");
		}

		// edge case where only one source and one target are inputted,
		// so no paths will be found. warn the user
		if (sources.size() == 1 && sources.equals(targets)) {
			JOptionPane.showMessageDialog(null,
					"The only source node is the same as the only target node. PathLinker will not compute any paths. Please add more nodes to the sources or targets.");
		}

		model.setCommonSourcesTargets();

		// parses the value inputted for k
		// if it is an invalid value, uses 200 by default and also appends the
		// error to the error message
		String kInput = _kTextField.getText().trim();
		try {
			int kValue = Integer.parseInt(kInput);
			model.setK(kValue);
		} catch (NumberFormatException exception) {
			errorMessage.append("Invalid number " + kInput + " entered for k. Using default k=200.\n");
			model.setK(200);
		}

		// gets the option for edge weight setting
		if (_unweighted.isSelected()) {
			model.setEdgeWeightSetting(EdgeWeightSetting.UNWEIGHTED);
		} else if (_weightedAdditive.isSelected()) {
			model.setEdgeWeightSetting(EdgeWeightSetting.ADDITIVE);
		} else if (_weightedProbabilities.isSelected()) {
			model.setEdgeWeightSetting(EdgeWeightSetting.PROBABILITIES);
		} else {
			errorMessage.append("No option selected for edge weights. Using unweighted as default.\n");
			model.setEdgeWeightSetting(EdgeWeightSetting.UNWEIGHTED);
		}

		// parses the value inputted for edge penalty
		// if it is an invalid value, uses 1.0 by default for multiplicative
		// option or 0.0 by default for additive option and also appends the
		// error to the error message
		String edgePenaltyInput = _edgePenaltyTextField.getText().trim();
		if (edgePenaltyInput.isEmpty()) {
			// nothing was inputted, use the default values for the setting
			if (model.getEdgeWeightSetting() == EdgeWeightSetting.PROBABILITIES) {
				model.setEdgePenalty(1.0);
			} else if (model.getEdgeWeightSetting() == EdgeWeightSetting.ADDITIVE) {
				model.setEdgePenalty(0.);
			}
		} else {
			// try to parse the user's input
			try {
				double edgePenalty = Double.parseDouble(edgePenaltyInput);
				model.setEdgePenalty(edgePenalty);
			} catch (NumberFormatException exception) {
				// invalid number was entered, invoked an exception
				if (model.getEdgeWeightSetting() == EdgeWeightSetting.PROBABILITIES) {
					errorMessage.append("Invalid number " + edgePenaltyInput
							+ " entered for edge penalty. Using default multiplicative edge penalty=1.0\n");
					model.setEdgePenalty(1.0);
				}

				if (model.getEdgeWeightSetting() == EdgeWeightSetting.ADDITIVE) {
					errorMessage.append("Invalid number " + edgePenaltyInput
							+ " entered for edge penalty. Using default additive edge penalty=0\n");
					model.setEdgePenalty(1.0);
				}
			}

			// valid number was entered, but not valid for the algorithm
			// i.e., negative number
			if (model.getEdgePenalty() <= 0 && model.getEdgeWeightSetting() == EdgeWeightSetting.PROBABILITIES) {
				errorMessage.append(
						"Invalid number entered for edge penalty with multiplicative option. Edge penalty for multiplicative option must be greater than 0. Using default penalty=1.0\n");
				model.setEdgePenalty(1.0);
			}

			if (model.getEdgePenalty() < 0 && model.getEdgeWeightSetting() == EdgeWeightSetting.ADDITIVE) {
				errorMessage.append(
						"Invalid number entered for edge penalty with additive option. Edge penalty for additive option must be greater than or equal to 0. Using default penalty=0\n");
				model.setEdgePenalty(0);
			}
		}

		_generateSubgraph = _subgraphOption.isSelected();

		// there is some error, tell the user
		if (errorMessage.length() > 0) {
			errorMessage.append("Continue anyway?");
			int choice = JOptionPane.showConfirmDialog(null, errorMessage.toString());
			if (choice != 0) {
				// quit if they say no or cancel
				return null;
			}
		}

		// checks if all the edges in the graph have weights.
		// if a weighted option was selected, but not all edges have weights
		// then we say something to the user.
		// we do this check for the unweighted option as well so in the case
		// that we have to deal with multi edges, we know whether or not to
		// average the weights or just delete the extra edges (see
		// averageMultiEdges method)
		CyNetwork originalNetwork = model.getOriginalNetwork();
		
		for (CyEdge edge : originalNetwork.getEdgeList()) {
			Double value = originalNetwork.getRow(edge).get("edge_weight", Double.class);

			if (value == null) {
				// not all the edges have weights (i.e., at least one of the
				// entries in the table is null)
				_allEdgesContainWeights = false;

				// only want to warn the user about not having all weighted
				// edges if a weighted option is selected
				if (model.getEdgeWeightSetting() != EdgeWeightSetting.UNWEIGHTED) {
					JOptionPane.showMessageDialog(null,
							"Weighted option was selected, but there exists at least one edge without a weight. Quitting...");
					return null;
				}
			}
		}

		// successful parsing
		return model;
	}

	/**
	 * Writes the ksp results to a table given the results from the ksp
	 * algorithm
	 *
	 * @param paths
	 *            a list of paths generated from the ksp algorithm
	 */
	private void writeResult(ArrayList<Path> paths, PathLinkerModel model) {
		// delete the copy of the network created for running pathlinker
		_network = null;

		// If no paths were found, then exit with this error
		// TODO This should be done before the empty kspSubgraph is created 
		if (paths.size() == 0) {
			JOptionPane.showMessageDialog(null, "No paths found.");
			return;
		}

		ResultFrame resultFrame = new ResultFrame(model.getOriginalNetwork(), paths);
		resultFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		resultFrame.setVisible(true);
		resultFrame.setSize(500, 700);
	}

	/**
	 * Generates a subgraph of the user supplied graph that contains only the
	 * nodes and edges that are in the k shortest paths
	 *
	 * @param paths
	 *            the list of paths generated by ksp algorithm
	 */
	private void createKSPSubgraph(ArrayList<Path> paths, PathLinkerModel model) {
		
		CyNetwork originalNetwork = model.getOriginalNetwork();
		HashSet<String> sourceNames = model.getSourceNames();
		HashSet<String> targetNames = model.getTargetNames();
		
		// creates a new network in the same network collection
		// as the original network
		CyRootNetwork root = ((CySubNetwork) originalNetwork).getRootNetwork();

		HashSet<CyNode> nodesToAdd = new HashSet<CyNode>();
		HashSet<CyEdge> edgesToAdd = new HashSet<CyEdge>();

		// keeps track of sources/targets in the ksp subgraph
		// to change their visual properties later
		HashSet<CyNode> sources = new HashSet<CyNode>();
		HashSet<CyNode> targets = new HashSet<CyNode>();

		for (Path currPath : paths) {
			// excluding supersource and supertarget
			for (int i = 1; i < currPath.size() - 2; i++) {
				CyNode node1 = currPath.get(i);
				CyNode node2 = currPath.get(i + 1);
				nodesToAdd.add(node1);
				nodesToAdd.add(node2);

				// check if the nodes are part of the sources or targets specified
				String node1name = originalNetwork.getRow(node1).get(CyNetwork.NAME, String.class);
				String node2name = originalNetwork.getRow(node2).get(CyNetwork.NAME, String.class);
				if (sourceNames.contains(node1name))
					sources.add(node1);
				if (targetNames.contains(node2name))
					targets.add(node2);

				// add all of the directed edges from node1 to node2
				List<CyEdge> edges = originalNetwork.getConnectingEdgeList(node1, node2, CyEdge.Type.DIRECTED);
				for (CyEdge edge : edges){
					// verifies the edges direction
					if (edge.getSource().equals(node1) && edge.getTarget().equals(node2))
						edgesToAdd.add(edge);
				}
				// also add all of the undirected edges from node1 to node2
				edgesToAdd.addAll(originalNetwork.getConnectingEdgeList(node1, node2, CyEdge.Type.UNDIRECTED));
			}
		}
		CyNetwork kspSubgraph = root.addSubNetwork(nodesToAdd, edgesToAdd);

		// sets the network name
		String subgraphName = "PathLinker-subnetwork-" + model.getK() + "-paths";
		kspSubgraph.getRow(kspSubgraph).set(CyNetwork.NAME, subgraphName);

		// creates the new network and its view
		CyNetworkView kspSubgraphView = _networkViewFactory.createNetworkView(kspSubgraph);
		_networkManager.addNetwork(kspSubgraph);
		_networkViewManager.addNetworkView(kspSubgraphView);

		Color targetColor = new Color(255, 223, 0);

		// use a visual bypass to color the sources and targets
		for (CyNode source : sources) {
			View<CyNode> currView = kspSubgraphView.getNodeView(source);
			currView.setLockedValue(BasicVisualLexicon.NODE_SHAPE, NodeShapeVisualProperty.DIAMOND);
			currView.setLockedValue(BasicVisualLexicon.NODE_FILL_COLOR, Color.CYAN);
		}
		for (CyNode target : targets) {
			View<CyNode> currView = kspSubgraphView.getNodeView(target);
			currView.setLockedValue(BasicVisualLexicon.NODE_SHAPE, NodeShapeVisualProperty.RECTANGLE);
			currView.setLockedValue(BasicVisualLexicon.NODE_FILL_COLOR, targetColor);

		}

		applyLayout(kspSubgraph, kspSubgraphView, model);
	}

	/**
	 * Applies a layout algorithm to the nodes If k <= 200, we apply a
	 * hierarchical layout Otherwise, we apply the default layout
	 * 
	 * @param kspSubgraphView
	 */
	private void applyLayout(CyNetwork kspSubgraph, CyNetworkView kspSubgraphView, PathLinkerModel model) {
		boolean hierarchical = model.getK() <= 200;

		// set node layout by applying the default layout algorithm
		CyLayoutAlgorithm algo = hierarchical ? _adapter.getCyLayoutAlgorithmManager().getLayout("hierarchical")
				: _adapter.getCyLayoutAlgorithmManager().getDefaultLayout();
		TaskIterator iter = algo.createTaskIterator(kspSubgraphView, algo.createLayoutContext(),
				CyLayoutAlgorithm.ALL_NODE_VIEWS, null);
		_adapter.getTaskManager().execute(iter);
		SynchronousTaskManager<?> synTaskMan = _adapter.getCyServiceRegistrar()
				.getService(SynchronousTaskManager.class);
		synTaskMan.execute(iter);
		_adapter.getVisualMappingManager().getVisualStyle(kspSubgraphView).apply(kspSubgraphView);
		kspSubgraphView.updateView();

		// if we applied the hierarchical layout, by default it is rendered
		// upside down
		// so we reflect all the nodes about the x axis
		if (hierarchical) {
			// sleep so the hierarchical layout can get applied
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			// reflect nodes about the x-axis because the default hierarchical
			// layout renders the nodes upside down
			// reflect nodes
			double maxY = Integer.MIN_VALUE;
			double minY = Integer.MAX_VALUE;

			// finds the midpoint x coordinate
			for (CyNode node : kspSubgraph.getNodeList()) {
				View<CyNode> nodeView = kspSubgraphView.getNodeView(node);
				double yCoord = nodeView.getVisualProperty(BasicVisualLexicon.NODE_Y_LOCATION);

				if (yCoord > maxY)
					maxY = yCoord;

				if (yCoord < minY)
					minY = yCoord;
			}

			double midY = (maxY + minY) / 2;

			// reflects each node about the midpoint x axis
			for (CyNode node : kspSubgraph.getNodeList()) {
				View<CyNode> nodeView = kspSubgraphView.getNodeView(node);
				double yCoord = nodeView.getVisualProperty(BasicVisualLexicon.NODE_Y_LOCATION);

				double newY = -1 * yCoord + 2 * midY;
				nodeView.setVisualProperty(BasicVisualLexicon.NODE_Y_LOCATION, newY);
			}

			kspSubgraphView.updateView();
		}
	}

	/**
	 * Sets up all the components in the panel
	 */
	private void initializePanelItems() {
		this.setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));

		_sourcesLabel = new JLabel("Sources separated by spaces, e.g., S1 S2 S3");
		_sourcesTextField = new JTextField(20);
		_sourcesTextField.setMaximumSize(new Dimension(Integer.MAX_VALUE, _sourcesTextField.getPreferredSize().height));

		_targetsLabel = new JLabel("Targets separated by spaces, e.g., T1 T2 T3");
		_targetsTextField = new JTextField(20);
		_targetsTextField.setMaximumSize(new Dimension(Integer.MAX_VALUE, _targetsTextField.getPreferredSize().height));

		_allowSourcesTargetsInPathsOption = new JCheckBox("<html>Allow sources and targets in paths</html>", false);
		_targetsSameAsSourcesOption = new JCheckBox("<html>Targets are identical to sources</html>", false);

		_kLabel = new JLabel("k (# of paths)");
		_kTextField = new JTextField(7);
		_kTextField.setMaximumSize(_kTextField.getPreferredSize());

		_edgePenaltyLabel = new JLabel("Edge penalty");
		_edgePenaltyTextField = new JTextField(7);
		_edgePenaltyTextField.setMaximumSize(_edgePenaltyTextField.getPreferredSize());

		_weightedOptionGroup = new ButtonGroup();
		_unweighted = new JRadioButton(
				"<html><b>Unweighted</b> - PathLinker will compute the k lowest cost paths, where the cost is the number of edges in the path.</html>");
		_weightedAdditive = new JRadioButton(
				"<html><b>Weighted, edge weights are additive</b> - PathLinker will compute the k lowest cost paths, where the cost is the sum of the edge weights.</html>");
		_weightedProbabilities = new JRadioButton(
				"<html><b>Weighted, edge weights are probabilities</b> - PathLinker will compute the k highest cost paths, where the cost is the product of the edge weights.</html>");
		_weightedOptionGroup.add(_unweighted);
		_weightedOptionGroup.add(_weightedAdditive);
		_weightedOptionGroup.add(_weightedProbabilities);

		_subgraphOption = new JCheckBox("<html>Generate a subnetwork of the nodes/edges involved in the k paths</html>",
				true);

		_runningMessage = new JLabel("PathLinker is running...");

		JPanel sourceTargetPanel = new JPanel();
		sourceTargetPanel.setLayout(new BoxLayout(sourceTargetPanel, BoxLayout.PAGE_AXIS));
		TitledBorder sourceTargetBorder = BorderFactory.createTitledBorder("Sources/Targets");
		sourceTargetPanel.setBorder(sourceTargetBorder);
		sourceTargetPanel.add(_sourcesLabel);
		sourceTargetPanel.add(_sourcesTextField);
		sourceTargetPanel.add(_targetsLabel);
		sourceTargetPanel.add(_targetsTextField);
		sourceTargetPanel.add(_allowSourcesTargetsInPathsOption);
		sourceTargetPanel.add(_targetsSameAsSourcesOption);
		this.add(sourceTargetPanel);

		JPanel kPanel = new JPanel();
		kPanel.setLayout(new BoxLayout(kPanel, BoxLayout.PAGE_AXIS));
		TitledBorder kBorder = BorderFactory.createTitledBorder("Algorithm");
		kPanel.setBorder(kBorder);
		kPanel.add(_kLabel);
		kPanel.add(_kTextField);
		kPanel.add(_edgePenaltyLabel);
		kPanel.add(_edgePenaltyTextField);
		this.add(kPanel);

		JPanel graphPanel = new JPanel();
		graphPanel.setLayout(new BoxLayout(graphPanel, BoxLayout.PAGE_AXIS));
		TitledBorder graphBorder = BorderFactory.createTitledBorder("Edge Weights");
		graphPanel.setBorder(graphBorder);
		graphPanel.add(_unweighted);
		graphPanel.add(_weightedAdditive);
		graphPanel.add(_weightedProbabilities);
		this.add(graphPanel);

		JPanel subgraphPanel = new JPanel();
		subgraphPanel.setLayout(new BoxLayout(subgraphPanel, BoxLayout.PAGE_AXIS));
		TitledBorder subgraphBorder = BorderFactory.createTitledBorder("Output");
		subgraphPanel.setBorder(subgraphBorder);
		subgraphPanel.add(_subgraphOption);
		this.add(subgraphPanel);

		_submitButton = new JButton("Submit");
		_submitButton.addActionListener(new SubmitButtonListener());
		this.add(_submitButton, BorderLayout.SOUTH);

		_runningMessage.setForeground(Color.BLUE);
		_runningMessage.setVisible(false);

		_unweighted.setSelected(true);
	}

	/**
	 * Sets the edge weight value in the network table
	 */
	private void setNetworkTableWeight(CyEdge e, double weight) {
		_network.getRow(e).set("edge_weight", weight);
	}

	@Override
	public Component getComponent() {
		return this;
	}

	@Override
	public CytoPanelName getCytoPanelName() {
		return CytoPanelName.WEST;
	}

	@Override
	public Icon getIcon() {
		return null;
	}

	@Override
	public String getTitle() {
		return "PathLinker";
	}
}
